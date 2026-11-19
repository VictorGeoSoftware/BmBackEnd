package com.bm.backend.services

import java.util.zip.CRC32

internal class InvalidReference : RuntimeException()

/** Checks the synthetic pilot's envelope only. No PDF objects or PNG pixels are interpreted. */
internal class TeachReferenceValidator {
    fun validate(bytes: ByteArray, size: Int, pdf: Boolean, checkpoint: () -> Unit = {}) {
        require(size in 0..bytes.size)
        checkpoint()
        if (pdf) validatePdf(bytes, size, checkpoint) else validatePng(bytes, size, checkpoint)
        checkpoint()
    }

    private fun validatePdf(bytes: ByteArray, size: Int, checkpoint: () -> Unit) {
        if (!bytes.matches(0, PDF_HEADER, size) || size <= 8 || bytes.unsigned(7) !in 0x30..0x37 || !bytes.lineBreak(8)) invalid()
        val headerEnd = if (bytes.unsigned(8) == CR && size > 9 && bytes.unsigned(9) == LF) 10 else 9
        var end = size
        fun skipBack(accept: (Int) -> Boolean) {
            while (end > headerEnd && accept(bytes.unsigned(end - 1))) {
                if (--end % BLOCK == 0) checkpoint()
            }
        }
        fun horizontal(byte: Int) = byte == 0x20 || byte == 0x09
        fun lineBreak(byte: Int) = byte == LF || byte == CR
        fun startsLine(token: Int): Boolean {
            var position = token
            while (position > headerEnd && horizontal(bytes.unsigned(position - 1))) {
                if (--position % BLOCK == 0) checkpoint()
            }
            return position >= headerEnd && bytes.lineBreak(position - 1)
        }
        fun previousLine() {
            skipBack(::horizontal)
            if (end <= headerEnd || !bytes.lineBreak(end - 1)) invalid()
            skipBack(::lineBreak)
            skipBack(::horizontal)
        }

        skipBack { horizontal(it) || lineBreak(it) }
        end -= EOF.size
        if (!bytes.matches(end, EOF, size)) invalid()
        previousLine()
        val decimalEnd = end
        skipBack { it in 0x30..0x39 }
        if (end == decimalEnd) invalid()
        var xref = 0L
        for (i in end until decimalEnd) {
            if (i % BLOCK == 0) checkpoint()
            xref = xref * 10 + bytes.unsigned(i) - 0x30
            if (xref >= size) invalid()
        }
        previousLine()
        val start = end - START_XREF.size
        if (!bytes.matches(start, START_XREF, size) || !startsLine(start)) invalid()
        if (xref < headerEnd || xref + XREF.size >= start || !bytes.matches(xref.toInt(), XREF, start) ||
            !startsLine(xref.toInt())) invalid()
        var afterXref = xref.toInt() + XREF.size
        while (afterXref < start && horizontal(bytes.unsigned(afterXref))) {
            if (++afterXref % BLOCK == 0) checkpoint()
        }
        if (afterXref >= start || !bytes.lineBreak(afterXref)) invalid()
    }

    private fun validatePng(bytes: ByteArray, size: Int, checkpoint: () -> Unit) {
        if (!bytes.matches(0, PNG_SIGNATURE, size)) invalid()
        val crc = CRC32()
        var offset = PNG_SIGNATURE.size
        var chunks = 0
        var seenIdat = false
        var hasImageData = false
        var idatEnded = false
        while (offset < size) {
            checkpoint()
            if (++chunks > 256 || size - offset < 12) invalid()
            val length = bytes.u32(offset)
            if (length > size - offset - 12) invalid()
            val type = offset + 4
            val data = type + 4
            val dataEnd = data + length.toInt()
            val next = dataEnd + 4
            if (chunks == 1 || bytes.matches(type, IHDR, data)) {
                if (chunks != 1 || !bytes.matches(type, IHDR, data) || length != 13L ||
                    bytes.u32(data) !in 1L..4096L || bytes.u32(data + 4) !in 1L..4096L) invalid()
            }
            if (bytes.matches(type, IDAT, data)) {
                if (idatEnded) invalid()
                seenIdat = true
                hasImageData = hasImageData || length > 0
            } else if (seenIdat) idatEnded = true

            crc.reset()
            for (position in type until dataEnd step BLOCK) {
                checkpoint()
                crc.update(bytes, position, minOf(BLOCK, dataEnd - position))
            }
            if (crc.value != bytes.u32(dataEnd)) invalid()
            if (bytes.matches(type, IEND, data)) {
                if (length != 0L || next != size || !hasImageData) invalid()
                return
            }
            offset = next
        }
        invalid()
    }

    private fun ByteArray.matches(offset: Int, token: ByteArray, end: Int) =
        offset >= 0 && offset <= end - token.size && token.indices.all { this[offset + it] == token[it] }

    private fun ByteArray.unsigned(index: Int) = this[index].toInt() and 0xff
    private fun ByteArray.lineBreak(index: Int) = unsigned(index) == CR || unsigned(index) == LF
    private fun ByteArray.u32(index: Int): Long =
        (0..3).fold(0L) { value, byte -> (value shl 8) or unsigned(index + byte).toLong() }

    private fun invalid(): Nothing = throw InvalidReference()

    private companion object {
        const val BLOCK = 8192
        const val LF = 0x0a
        const val CR = 0x0d
        val PDF_HEADER = "%PDF-1.".encodeToByteArray()
        val START_XREF = "startxref".encodeToByteArray()
        val XREF = "xref".encodeToByteArray()
        val EOF = "%%EOF".encodeToByteArray()
        val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
        val IHDR = "IHDR".encodeToByteArray()
        val IDAT = "IDAT".encodeToByteArray()
        val IEND = "IEND".encodeToByteArray()
    }
}
