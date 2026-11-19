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
private const val MANIFEST_LIMIT = 32 * 1024
private const val ANSWER_LIMIT = 4 * 1024
private const val BODY_LIMIT = 4 + MANIFEST_LIMIT + WORKBOOK_BODY_LIMIT + 2 * REFERENCE_BODY_LIMIT
private val SIZE = Regex("0|[1-9][0-9]*")
private val SHA256 = Regex("[0-9a-f]{64}")

internal suspend fun readClarificationFrame(channel: ByteReadChannel, checkpoint: () -> Unit): TeachClarificationFrame {
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

    val manifestSize = Integer.toUnsignedLong(ByteBuffer.wrap(segment(4)).int)
    if (manifestSize == 0L) throw InvalidTeachUpload()
    if (manifestSize > MANIFEST_LIMIT) throw TeachUploadTooLarge()
    val text = decodeUtf8(segment(manifestSize.toInt()))
    checkFlatJson(text)
    val tuple = try { Json.parseToJsonElement(text) as? JsonArray } catch (_: SerializationException) { null }
        ?: throw InvalidTeachUpload()
    fun primitive(index: Int) = tuple.getOrNull(index) as? JsonPrimitive ?: throw InvalidTeachUpload()
    fun string(index: Int) = primitive(index).takeIf { it.isString }?.content ?: throw InvalidTeachUpload()
    fun size(index: Int, max: Int): Int {
        val value = primitive(index).takeIf { !it.isString && SIZE.matches(it.content) } ?: throw InvalidTeachUpload()
        return value.content.toIntOrNull()?.takeIf { it <= max } ?: throw TeachUploadTooLarge()
    }
    if (primitive(0).let { it.isString || it.content != "1" }) throw InvalidTeachUpload()
    val reply = when (string(1)) {
        "ask" -> false
        "reply" -> true
        else -> throw InvalidTeachUpload()
    }
    if (tuple.size != if (reply) 7 else 3) throw InvalidTeachUpload()
    val xlsx = size(2, WORKBOOK_BODY_LIMIT).takeIf { it > 0 } ?: throw InvalidTeachUpload()
    val pdf = if (reply) size(3, REFERENCE_BODY_LIMIT) else 0
    val png = if (reply) size(4, REFERENCE_BODY_LIMIT) else 0
    val expected = if (reply) string(5).takeIf(SHA256::matches) ?: throw InvalidTeachUpload() else null
    val answer = if (reply) string(6).also(::validateAnswer) else null
    val hasher = MessageDigest.getInstance("SHA-256")
    val workbook = segment(xlsx, hasher)
    val pdfBytes = if (pdf > 0) segment(pdf) else null
    val pngBytes = if (png > 0) segment(png) else null
    val excess = channel.readAvailable(ByteArray(1), 0, 1)
    checkpoint()
    if (excess >= 0) {
        if (4 + manifestSize + xlsx + pdf + png == BODY_LIMIT.toLong()) throw TeachUploadTooLarge()
        throw InvalidTeachUpload()
    }
    return TeachClarificationFrame(workbook, HexFormat.of().formatHex(hasher.digest()), expected, answer, pdfBytes, pngBytes)
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

private fun validateAnswer(answer: String) {
    if (answer.all(::isUnicodeSpace)) throw InvalidTeachUpload()
    // JSON escapes can produce an isolated surrogate even when the frame itself is valid UTF-8.
    val bytes = try { Charsets.UTF_8.newEncoder().encode(CharBuffer.wrap(answer)).remaining() }
    catch (_: CharacterCodingException) { throw InvalidTeachUpload() }
    if (bytes > ANSWER_LIMIT) throw TeachUploadTooLarge()
}
