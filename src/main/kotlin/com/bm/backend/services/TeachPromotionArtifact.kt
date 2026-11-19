package com.bm.backend.services

import com.bm.backend.models.TeachPromotionDiff
import com.bm.backend.models.TeachRegisteredSkillRevision
import com.bm.backend.repositories.ports.TeachProposalStoreUnavailable
import kotlinx.serialization.json.*
import java.security.MessageDigest

private const val approvedRuleJsonDigest = "44a690dab4ca94f99e0e0f4baaebaefb62d82d6400d9b69968c9578beaa0859e"
private fun digest(bytes: ByteArray) = sha256Hex(MessageDigest.getInstance("SHA-256").digest(bytes))

/** Fixed UTF-8 field order; entire rule must be the closed audited synthetic map. */
internal fun projectTeachPromotion(entry: TeachRegisteredSkillRevision, manifest: String): TeachPromotionDiff {
    val rule = entry.rule
    if (entry.supplierKey != GESTERNOVA_SUPPLIER || entry.profileKey != GESTERNOVA_PREVIEW_PROFILE ||
        entry.semantics != "gesternova-fixed-extractor/1" || entry.manifest != manifest || !teachRevisionId.matches(manifest) ||
        !teachRevisionId.matches(entry.workbookSha256) || !teachRevisionId.matches(entry.revisionId) ||
        rule != gesternovaPreviewRule || previewRevision(entry.workbookSha256, rule) != entry.revisionId) throw TeachProposalStoreUnavailable()
    fun header(a1: String, label: String) = buildJsonObject { put("a1", a1); put("label", label) }
    val projectedRule = buildJsonObject {
        put("kind", rule.kind); put("sheet", rule.sheet)
        put("candidateRows", JsonArray(rule.candidateRows.map(::JsonPrimitive)))
        put("fixedRows", JsonArray(rule.fixedRows.map(::JsonPrimitive)))
        put("tariff", rule.tariff); put("otherTariff", rule.otherTariff); put("fixedType", rule.fixedType)
        put("excludedTypes", JsonArray(rule.excludedTypes.map(::JsonPrimitive)))
        put("headers", JsonArray(rule.headers.map { header(it.a1, it.label) }))
        putJsonArray("bindings") {
            rule.bindings.forEach { binding ->
                addJsonObject {
                    put("term", binding.term); put("period", binding.period); put("unit", binding.unit)
                    put("header", header(binding.header.a1, binding.header.label))
                }
            }
        }
        put("absentHeaders", JsonArray(rule.absentHeaders.map { header(it.a1, it.label) }))
        put("styleSha256", rule.styleSha256); put("decimalPolicy", rule.decimalPolicy)
    }
    // Independent wire pin: a newly edited registry rule must not silently expand this output contract.
    if (digest(projectedRule.toString().encodeToByteArray()) != approvedRuleJsonDigest) throw TeachProposalStoreUnavailable()
    val artifact = buildJsonObject {
        put("schema", "bm.teach.promotion-artifact/v1")
        put("supplierKey", entry.supplierKey)
        put("profileKey", entry.profileKey)
        put("revisionId", entry.revisionId)
        put("semantics", entry.semantics)
        put("manifest", entry.manifest)
        put("rule", projectedRule)
    }.toString()
    val bytes = artifact.encodeToByteArray()
    if (bytes.size !in 1..8192) throw TeachProposalStoreUnavailable()
    return TeachPromotionDiff(entry.revisionId, artifact, digest(bytes))
}
