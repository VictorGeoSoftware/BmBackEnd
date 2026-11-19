package com.bm.backend.services

import com.bm.backend.models.TeachRegisteredSkillRevision
import java.security.MessageDigest

internal val teachRevisionId = Regex("[0-9a-f]{64}")
internal val teachFixtureId = Regex("[0-9a-f]{32}")
private const val SEMANTICS = "gesternova-fixed-extractor/1"

internal fun teachTuple(fields: List<String>): ByteArray = fields.joinToString("") {
    "${it.encodeToByteArray().size}:$it"
}.encodeToByteArray()

/** An immutable, exact V2 lookup, never an alias to the currently active revision. */
internal class TeachActivationRegistry(pins: Set<String>) {
    private val byRevision = pins.associateBy { previewRevision(it, gesternovaPreviewRule) }
    init {
        require(pins.size in 1..3 && GESTERNOVA_PREVIEW_HASH in pins && pins.all(teachRevisionId::matches))
    }
    val manifest = sha256Hex(MessageDigest.getInstance("SHA-256").digest(teachTuple(
        listOf("bm.teach.qa-registry/v1", SEMANTICS, byRevision.size.toString()) +
            byRevision.toSortedMap().flatMap { (revision, digest) ->
                listOf(GESTERNOVA_SUPPLIER, GESTERNOVA_PREVIEW_PROFILE, digest, revision)
            },
    )))

    fun containsPin(digest: String) = digest in byRevision.values
    fun resolves(supplier: String, revision: String, digest: String? = null): Boolean =
        supplier == GESTERNOVA_SUPPLIER && byRevision[revision]?.let { digest == null || digest == it } == true

    fun exact(supplier: String, revision: String): TeachRegisteredSkillRevision? =
        if (supplier != GESTERNOVA_SUPPLIER) null else byRevision[revision]?.let { pin ->
            TeachRegisteredSkillRevision(supplier, revision, GESTERNOVA_PREVIEW_PROFILE, pin, SEMANTICS, manifest)
        }
}
