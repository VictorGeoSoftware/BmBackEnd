package com.bm.backend.services

import com.bm.backend.models.TeachActivationClaim
import com.bm.backend.models.TeachActiveSkill
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal class TeachActivationConflict : RuntimeException()
internal class TeachActivationUnavailable : RuntimeException()
internal fun activationCheck(valid: Boolean) { if (!valid) throw TeachActivationConflict() }

internal val teachSyntheticActor = Regex("[!-~]{1,64}")
internal const val TEACH_ACTIVATION_TTL_SECONDS = 300L
internal const val MAX_SAFE_JSON_INTEGER = 9007199254740991L
internal fun teachGeneration(value: String): Long? = value.toLongOrNull()?.takeIf { it >= 0 && it.toString() == value }
internal fun validTeachActive(revision: String?, generation: String): Boolean =
    teachGeneration(generation) != null && if (revision == null) generation == "0" else generation != "0" && teachRevisionId.matches(revision)

internal class TeachActivationReceipts(key: ByteArray, storeId: String, manifest: String) {
    private val secret = SecretKeySpec(key.also { require(it.size == 32) }, "HmacSHA256")
    private val context = listOf("bm.teach.qa-activation/v1", "qa", storeId, manifest)
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()
    private val wire = Regex("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]{43}")

    private fun mac(payload: ByteArray) = Mac.getInstance("HmacSHA256").run { init(secret); doFinal(payload) }

    fun issue(claim: TeachActivationClaim): String {
        val payload = teachTuple(context + listOf(claim.actor, claim.supplierKey, claim.profileKey, claim.workbookSha256,
            claim.revisionId, claim.expected.revisionId.orEmpty(), claim.expected.generation, claim.nonce,
            claim.issuedAt.toString(), claim.expiresAt.toString()))
        return "${encoder.encodeToString(payload)}.${encoder.encodeToString(mac(payload))}".also { check(it.length <= 1536) }
    }

    fun verify(value: String): TeachActivationClaim {
        activationCheck(value.length <= 1536 && wire.matches(value))
        val (payloadPart, signaturePart) = value.split('.')
        fun decode(part: String): ByteArray = try {
            decoder.decode(part).also { activationCheck(encoder.encodeToString(it) == part) }
        } catch (_: IllegalArgumentException) { throw TeachActivationConflict() }
        val payload = decode(payloadPart)
        activationCheck(MessageDigest.isEqual(mac(payload), decode(signaturePart)))
        activationCheck(payload.all { it.toInt() in 32..126 }) // All fields in this protocol are ASCII.
        val text = payload.decodeToString()
        var index = 0
        val fields = List(14) {
            val colon = text.indexOf(':', index)
            activationCheck(colon - index in 1..4)
            val size = teachGeneration(text.substring(index, colon))?.toInt() ?: throw TeachActivationConflict()
            activationCheck(size <= text.length - colon - 1)
            text.substring(colon + 1, colon + 1 + size).also { index = colon + 1 + size }
        }
        activationCheck(index == text.length && fields.take(4) == context)
        val expected = TeachActiveSkill(fields[9].ifEmpty { null }, fields[10])
        val issued = teachGeneration(fields[12]) ?: throw TeachActivationConflict()
        val expires = teachGeneration(fields[13]) ?: throw TeachActivationConflict()
        activationCheck(teachSyntheticActor.matches(fields[4]) && teachRevisionId.matches(fields[7]) &&
            teachRevisionId.matches(fields[8]) && validTeachActive(expected.revisionId, expected.generation) &&
            teachFixtureId.matches(fields[11]) && expires <= MAX_SAFE_JSON_INTEGER && expires - issued == TEACH_ACTIVATION_TTL_SECONDS)
        return TeachActivationClaim(fields[4], fields[5], fields[6], fields[7], fields[8], expected, fields[11], issued, expires)
    }
}
