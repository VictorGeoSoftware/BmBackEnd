package com.bm.backend.services

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.util.Locale

internal class InvalidWorkbook : RuntimeException()
internal class WorkbookBudgetExceeded : RuntimeException()

internal fun workbookCheck(value: Boolean) {
    if (!value) throw InvalidWorkbook()
}

/** Prove framing and count real records before any eager ZIP reader sees the archive. */
internal fun preflightWorkbookZip(bytes: ByteArray, size: Int, maxEntries: Int, checkpoint: () -> Unit): Map<String, TeachWorkbookZipMember> {
    workbookCheck(size in 22..bytes.size)
    fun u16(p: Int) = (bytes[p].toInt() and 255) or ((bytes[p + 1].toInt() and 255) shl 8)
    fun u32(p: Int) = u16(p).toLong() or (u16(p + 2).toLong() shl 16)
    fun offset(p: Int): Int = u32(p).also { workbookCheck(it <= size) }.toInt()
    fun extras(start: Int, end: Int) {
        var p = start
        while (p < end) {
            checkpoint()
            workbookCheck(end - p >= 4)
            val id = u16(p)
            // ZIP64 and alternate Unicode-path metadata are not needed by this profile.
            workbookCheck(id != 0x0001 && id != 0x7075)
            val length = u16(p + 2)
            p += 4 + length
            workbookCheck(p <= end)
        }
    }

    val end = size - 22
    workbookCheck(u32(end) == 0x06054b50L && u16(end + 4) == 0 && u16(end + 6) == 0 && u16(end + 20) == 0)
    // Commons checks this position for a ZIP64 locator before opening the directory.
    workbookCheck(end < 20 || u32(end - 20) != 0x07064b50L)
    val directory = offset(end + 16)
    workbookCheck(directory <= end && offset(end + 12) == end - directory)
    val members = linkedMapOf<String, TeachWorkbookZipMember>()
    val folded = hashSetOf<String>()
    val ranges = sortedMapOf<Int, Int>()
    var p = directory
    while (p < end) {
        checkpoint()
        workbookCheck(end - p >= 46 && u32(p) == 0x02014b50L)
        if (members.size == maxEntries) throw WorkbookBudgetExceeded()
        val needed = u16(p + 6)
        val flags = u16(p + 8)
        val method = u16(p + 10)
        // Only UTF-8 and (for deflate) compression-level hints are permitted.
        workbookCheck(needed in 10..20 && method in setOf(0, 8) && flags and (if (method == 8) 0x0806 else 0x0800).inv() == 0)
        val packed = offset(p + 20)
        val unpacked = u32(p + 24)
        workbookCheck(unpacked != 0xffffffffL && (method != 0 || packed.toLong() == unpacked))
        val nameLength = u16(p + 28)
        val nameEnd = p + 46 + nameLength
        val extraEnd = nameEnd + u16(p + 30)
        val next = extraEnd + u16(p + 32)
        workbookCheck(nameLength > 0 && next <= end && u16(p + 34) == 0)
        val mode = u32(p + 38).ushr(16).toInt() and 0xf000
        workbookCheck(mode == 0 || mode == 0x8000 || mode == 0x4000)
        val name = try {
            Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes, p + 46, nameLength)).toString()
        } catch (_: CharacterCodingException) { throw InvalidWorkbook() }
        workbookCheck(safeWorkbookPart(name))
        val key = name.removeSuffix("/").lowercase(Locale.ROOT)
        workbookCheck(folded.add(key))
        extras(nameEnd, extraEnd)

        val local = offset(p + 42)
        workbookCheck(directory - local >= 30 && u32(local) == 0x04034b50L)
        workbookCheck(u16(local + 4) == needed && u16(local + 6) == flags && u16(local + 8) == method)
        val crc = u32(p + 16)
        workbookCheck(u32(local + 14) == crc && u32(local + 18) == packed.toLong() && u32(local + 22) == unpacked)
        workbookCheck(u16(local + 26) == nameLength)
        val localNameEnd = local + 30 + nameLength
        val data = localNameEnd + u16(local + 28)
        workbookCheck(data <= directory && packed <= directory - data)
        workbookCheck((0 until nameLength).all { bytes[local + 30 + it] == bytes[p + 46 + it] })
        extras(localNameEnd, data)
        workbookCheck(ranges.put(local, data + packed) == null)
        if (name.endsWith('/')) workbookCheck(unpacked == 0L)
        members[name] = TeachWorkbookZipMember(local, data, packed, unpacked, crc, method, flags)
        p = next
    }
    workbookCheck(members.size == u16(end + 8) && members.size == u16(end + 10))
    var cursor = 0
    for ((start, stop) in ranges) {
        workbookCheck(start == cursor)
        cursor = stop
    }
    workbookCheck(cursor == directory)
    val paths = members.keys.mapTo(java.util.TreeSet()) { it.lowercase(Locale.ROOT) }
    for (file in paths.filterNot { it.endsWith('/') }) {
        val prefix = "$file/"
        workbookCheck(paths.ceiling(prefix)?.startsWith(prefix) != true)
    }
    return members
}

internal fun safeWorkbookPart(name: String): Boolean = name.isNotEmpty() &&
    name.none { it in "\\:%?#" || it.isWhitespace() || it.isISOControl() || isWorkbookBidi(it) || it == '\uFEFF' } &&
    name.removeSuffix("/").split('/').all { it.isNotEmpty() && !it.endsWith('.') }

internal fun isWorkbookBidi(c: Char) = c == '\u061c' || c in '\u200e'..'\u200f' ||
    c in '\u202a'..'\u202e' || c in '\u2066'..'\u2069'
