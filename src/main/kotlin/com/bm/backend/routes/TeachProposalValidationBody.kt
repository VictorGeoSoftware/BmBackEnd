package com.bm.backend.routes

import com.bm.backend.models.TeachProposalValidationConfirmation
import io.ktor.utils.io.ByteReadChannel
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.util.UUID
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

internal const val PROPOSAL_VALIDATION_PATH = "/teach-me/proposal-validation"
internal const val PROPOSAL_VALIDATION_LIMIT = 1024
private val canonicalRef = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
internal fun parseTeachProposalValidation(text: String): TeachProposalValidationConfirmation {
    var pos = 0
    fun space() { while (pos < text.length && text[pos] in " \t\r\n") pos++ }
    fun delimiter(value: Char) {
        space()
        if (text.getOrNull(pos++) != value) throw InvalidTeachUpload()
    }
    // Read each original key before constructing a value, including escaped spellings of a key.
    fun string(): String {
        space()
        val start = pos
        if (text.getOrNull(pos++) != '"') throw InvalidTeachUpload()
        while (pos < text.length) {
            when (val c = text[pos++]) {
                '\\' -> pos++
                '"' -> return try { Json.decodeFromString<String>(text.substring(start, pos)) }
                    catch (_: SerializationException) { throw InvalidTeachUpload() }
                else -> if (c.code < 32) throw InvalidTeachUpload()
            }
        }
        throw InvalidTeachUpload()
    }
    delimiter('{')
    var ref: String? = null
    var affirmed = false
    repeat(2) { index ->
        if (index != 0) delimiter(',')
        val key = string()
        delimiter(':')
        when (key) {
            "validationRef" -> {
                if (ref != null) throw InvalidTeachUpload()
                ref = string().takeIf(canonicalRef::matches) ?: throw InvalidTeachUpload()
            }
            "confirmExpectedPrices" -> {
                space()
                if (affirmed || !text.startsWith("true", pos)) throw InvalidTeachUpload()
                pos += 4
                affirmed = true
            }
            else -> throw InvalidTeachUpload()
        }
    }
    delimiter('}')
    space()
    if (pos != text.length || !affirmed) throw InvalidTeachUpload()
    return TeachProposalValidationConfirmation(UUID.fromString(ref ?: throw InvalidTeachUpload()))
}

internal suspend fun readTeachProposalValidation(channel: ByteReadChannel, length: Long, checkpoint: () -> Unit): TeachProposalValidationConfirmation {
    val bytes = ByteArray(PROPOSAL_VALIDATION_LIMIT + 1)
    var size = 0
    while (size < bytes.size) {
        checkpoint()
        val read = channel.readAvailable(bytes, size, bytes.size - size)
        if (read < 0) break
        size += read
    }
    checkpoint()
    if (size > PROPOSAL_VALIDATION_LIMIT) throw TeachUploadTooLarge()
    if (length != size.toLong()) throw InvalidTeachUpload()
    val text = try { Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes, 0, size)).toString() }
        catch (_: CharacterCodingException) { throw InvalidTeachUpload() }
    return parseTeachProposalValidation(text)
}
