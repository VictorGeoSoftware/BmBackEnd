package com.bm.backend.routes

import com.bm.backend.models.TeachActivationRequest
import com.bm.backend.services.teachRevisionId
import com.bm.backend.services.validTeachActive
import io.ktor.utils.io.ByteReadChannel
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

internal const val QA_PREVIEW_MEDIA_TYPE = "application/vnd.bm.teach-draft-preview-qa-pilot"
internal const val ACTIVATION_PATH = "/teach-me/activation"

/** Closed flat JSON: tokenize original keys (including escapes) before map construction. */
internal suspend fun readTeachActivation(channel: ByteReadChannel, checkpoint: () -> Unit): TeachActivationRequest {
    val bytes = ByteArray(2049)
    var size = 0
    while (size < bytes.size) {
        checkpoint()
        val read = channel.readAvailable(bytes, size, bytes.size - size)
        if (read < 0) break
        size += read
    }
    checkpoint()
    if (size == bytes.size) throw TeachUploadTooLarge()
    val text = try { Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes, 0, size)).toString() }
        catch (_: CharacterCodingException) { throw InvalidTeachUpload() }
    return parseTeachActivation(text)
}

internal fun parseTeachActivation(text: String): TeachActivationRequest {
    val expected = setOf("revisionId", "expectedRevisionId", "expectedGeneration", "receipt")
    val fields = mutableMapOf<String, String?>()
    var pos = 0
    fun space() { while (pos < text.length && text[pos] in " \t\r\n") pos++ }
    fun delimiter(char: Char) {
        space()
        if (text.getOrNull(pos++) != char) throw InvalidTeachUpload()
    }
    fun string(): String {
        space()
        val start = pos
        if (text.getOrNull(pos++) != '"') throw InvalidTeachUpload()
        while (pos < text.length) {
            val c = text[pos++]
            if (c.code < 32) throw InvalidTeachUpload()
            if (c == '\\') pos++
            if (c == '"') {
                return try { Json.decodeFromString<String>(text.substring(start, pos)) }
                catch (_: SerializationException) { throw InvalidTeachUpload() }
            }
        }
        throw InvalidTeachUpload()
    }
    delimiter('{')
    repeat(4) { index ->
        if (index > 0) delimiter(',')
        val key = string()
        if (key !in expected || key in fields) throw InvalidTeachUpload()
        delimiter(':')
        space()
        fields[key] = if (key == "expectedRevisionId" && text.startsWith("null", pos)) { pos += 4; null } else string()
    }
    delimiter('}')
    space()
    val revision = fields.getValue("revisionId") ?: throw InvalidTeachUpload()
    val previous = fields.getValue("expectedRevisionId")
    val generation = fields.getValue("expectedGeneration") ?: throw InvalidTeachUpload()
    val receipt = fields.getValue("receipt") ?: throw InvalidTeachUpload()
    if (pos != text.length || !teachRevisionId.matches(revision) || !validTeachActive(previous, generation)) throw InvalidTeachUpload()
    return TeachActivationRequest(revision, previous, generation, receipt)
}
