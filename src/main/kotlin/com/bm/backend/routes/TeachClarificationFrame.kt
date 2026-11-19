package com.bm.backend.routes

import com.bm.backend.models.TeachClarificationFrame
import com.bm.backend.services.isUnicodeSpace
import io.ktor.utils.io.ByteReadChannel
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.security.MessageDigest
import java.util.HexFormat

internal const val CLARIFICATION_MEDIA_TYPE = "application/vnd.bm.teach-clarification"
internal const val TEACH_MANIFEST_LIMIT = 32 * 1024
private const val ANSWER_LIMIT = 4 * 1024
private const val BODY_LIMIT = 4 + TEACH_MANIFEST_LIMIT + WORKBOOK_BODY_LIMIT + 2 * REFERENCE_BODY_LIMIT
private val SIZE = Regex("0|[1-9][0-9]*")
private val SHA256 = Regex("[0-9a-f]{64}")

internal suspend fun readClarificationFrame(channel: ByteReadChannel, checkpoint: () -> Unit): TeachClarificationFrame {
    val reader = TeachFrameReader(channel, checkpoint)
    val tuple = reader.manifest()
    val reply = when (tuple.string(1)) {
        "ask" -> false
        "reply" -> true
        else -> throw InvalidTeachUpload()
    }
    if (tuple.size != if (reply) 7 else 3) throw InvalidTeachUpload()
    val xlsx = tuple.workbookSize()
    val pdf = if (reply) tuple.sizeAt(3, REFERENCE_BODY_LIMIT) else 0
    val png = if (reply) tuple.sizeAt(4, REFERENCE_BODY_LIMIT) else 0
    val expected = if (reply) tuple.digest(5) else null
    val answer = if (reply) tuple.string(6).also(::validateAnswer) else null
    val hasher = MessageDigest.getInstance("SHA-256")
    val workbook = reader.segment(xlsx, hasher)
    val pdfBytes = if (pdf > 0) reader.segment(pdf) else null
    val pngBytes = if (png > 0) reader.segment(png) else null
    reader.requireEnd(xlsx + pdf + png, BODY_LIMIT)
    return TeachClarificationFrame(workbook, hasher.hexDigest(), expected, answer, pdfBytes, pngBytes)
}

internal fun MessageDigest.hexDigest(): String = HexFormat.of().formatHex(digest())

/** Shared streaming primitives; each wire has its own tuple and tail budgets. */
internal class TeachFrameReader(private val channel: ByteReadChannel, private val checkpoint: () -> Unit) {
    private var manifestSize = 0

    suspend fun segment(size: Int, digest: MessageDigest? = null): ByteArray {
        checkpoint()
        val bytes = ByteArray(size)
        var offset = 0
        while (offset < size) {
            checkpoint()
            val n = channel.readAvailable(bytes, offset, minOf(8192, size - offset))
            checkpoint()
            if (n < 0) throw InvalidTeachUpload()
            digest?.update(bytes, offset, n)
            offset += n
        }
        return bytes
    }

    suspend fun manifest(): TeachFrameTuple {
        val length = Integer.toUnsignedLong(ByteBuffer.wrap(segment(4)).int)
        if (length == 0L) throw InvalidTeachUpload()
        if (length > TEACH_MANIFEST_LIMIT) throw TeachUploadTooLarge()
        manifestSize = length.toInt()
        val text = decodeUtf8(segment(manifestSize))
        checkFlatJson(text)
        val tuple = try { Json.parseToJsonElement(text) as? JsonArray } catch (_: SerializationException) { null }
            ?: throw InvalidTeachUpload()
        return TeachFrameTuple(tuple)
    }

    suspend fun requireEnd(payloadBytes: Int, limit: Int) {
        val excess = channel.readAvailable(ByteArray(1), 0, 1)
        checkpoint()
        if (excess >= 0) {
            if (4 + manifestSize + payloadBytes == limit) throw TeachUploadTooLarge()
            throw InvalidTeachUpload()
        }
    }
}

internal class TeachFrameTuple(private val tuple: JsonArray) {
    private fun primitive(index: Int) = tuple.getOrNull(index) as? JsonPrimitive ?: throw InvalidTeachUpload()
    init { if (primitive(0).let { it.isString || it.content != "1" }) throw InvalidTeachUpload() }
    val size get() = tuple.size
    fun string(index: Int) = primitive(index).takeIf { it.isString }?.content ?: throw InvalidTeachUpload()
    fun digest(index: Int) = string(index).takeIf(SHA256::matches) ?: throw InvalidTeachUpload()
    fun workbookSize() = sizeAt(2, WORKBOOK_BODY_LIMIT).takeIf { it > 0 } ?: throw InvalidTeachUpload()
    fun sizeAt(index: Int, max: Int): Int {
        val value = primitive(index).takeIf { !it.isString && SIZE.matches(it.content) } ?: throw InvalidTeachUpload()
        return value.content.toIntOrNull()?.takeIf { it <= max } ?: throw TeachUploadTooLarge()
    }
}

private fun decodeUtf8(bytes: ByteArray): String = try {
    Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
} catch (_: CharacterCodingException) { throw InvalidTeachUpload() }

/** The wire is a flat tuple of scalars. Rule out nested JSON before the library builds a tree. */
private fun checkFlatJson(text: String) {
    var inString = false
    var escaped = false
    var opened = false
    var closed = false
    for (c in text) {
        if (inString) {
            if (c < ' ') throw InvalidTeachUpload()
            if (escaped) escaped = false
            else if (c == '\\') escaped = true
            else if (c == '"') inString = false
        } else {
            when (c) {
                ' ', '\t', '\r', '\n' -> Unit
                '[' -> { if (opened) throw InvalidTeachUpload(); opened = true }
                ']' -> { if (!opened || closed) throw InvalidTeachUpload(); closed = true }
                else -> {
                    if (!opened || closed || c == '{' || c == '}' || c < ' ') throw InvalidTeachUpload()
                    if (c == '"') inString = true
                }
            }
        }
    }
    if (!closed || inString) throw InvalidTeachUpload()
}

internal fun validateAnswer(answer: String) {
    if (answer.all(::isUnicodeSpace)) throw InvalidTeachUpload()
    // JSON escapes can produce an isolated surrogate even when the frame itself is valid UTF-8.
    val bytes = try { Charsets.UTF_8.newEncoder().encode(CharBuffer.wrap(answer)).remaining() }
    catch (_: CharacterCodingException) { throw InvalidTeachUpload() }
    if (bytes > ANSWER_LIMIT) throw TeachUploadTooLarge()
}
