package com.bm.backend.services

import com.bm.backend.models.TeachPromotionProposal
import com.bm.backend.repositories.ports.TeachPromotionProviderUnavailable
import com.bm.backend.repositories.ports.TeachProposalStoreUnavailable
import com.bm.backend.testing.TeachPromotionFake
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.security.MessageDigest
import java.util.UUID
import kotlin.test.*

internal class TeachProductionPromotionServiceTest {
    private val second = "1".repeat(64)
    private val registry = TeachActivationRegistry(setOf(GESTERNOVA_PREVIEW_HASH, second))
    private val a = checkNotNull(registry.exact("gesternova", previewRevision(GESTERNOVA_PREVIEW_HASH, gesternovaPreviewRule)))
    private val b = checkNotNull(registry.exact("gesternova", previewRevision(second, gesternovaPreviewRule)))
    private val expectedRule = """{"kind":"fixed_period_prices","sheet":"Tarifas","candidateRows":[15,16,17,18,19,20],"fixedRows":[16,17,18],"tariff":"2.0TD","otherTariff":"3.0TD","fixedType":"Precio por Periodos","excludedTypes":["Indexado"],"headers":[{"a1":"F14","label":"Tarifa"},{"a1":"G14","label":"Tipo precio"},{"a1":"I14","label":"Producto"}],"bindings":[{"term":"energy","period":"P1","unit":"€/kWh","header":{"a1":"K14","label":"TEA_P1 €/kWh"}},{"term":"power","period":"P1","unit":"€/kWdia","header":{"a1":"S14","label":"TP_P1 (€/kWdia)"}},{"term":"power","period":"P2","unit":"€/kWdia","header":{"a1":"T14","label":"TP_P2 (€/kWdia)"}}],"absentHeaders":[{"a1":"L14","label":"TEA_P2 €/kWh"},{"a1":"M14","label":"TEA_P3 €/kWh"}],"styleSha256":"ceabc26839ae8c5285c667366bffa538562f76e219eb4f2f5188626232d96900","decimalPolicy":"unsigned-plain-6i-12f-no-exponent"}"""

    @Test fun `projection is exact ordered data only for each verified historical V2 identity`() {
        val diffs = listOf(a, b).map { entry ->
            val diff = projectTeachPromotion(entry, registry.manifest)
            assertEquals("""{"schema":"bm.teach.promotion-artifact/v1","supplierKey":"gesternova","profileKey":"gesternova-20td-sanitized","revisionId":"${entry.revisionId}","semantics":"gesternova-fixed-extractor/1","manifest":"${registry.manifest}","rule":$expectedRule}""", diff.artifact)
            assertEquals(sha256Hex(MessageDigest.getInstance("SHA-256").digest(diff.artifact.encodeToByteArray())), diff.digest)
            assertEquals("skills-fixture/gesternova/${entry.revisionId}.json", diff.path)
            assertTrue(entry.workbookSha256 !in diff.artifact, "The teaching pin is internal")
            diff
        }
        assertEquals(Json.parseToJsonElement(diffs[0].artifact).jsonObject["rule"], Json.parseToJsonElement(diffs[1].artifact).jsonObject["rule"])
        assertNotEquals(diffs[0].digest, diffs[1].digest)
        val altered = a.rule.copy(sheet = "Fixture-secret-override")
        for (invalid in listOf(a.copy(rule = altered, revisionId = previewRevision(a.workbookSha256, altered)),
            a.copy(revisionId = b.revisionId), a.copy(workbookSha256 = b.workbookSha256), a.copy(semantics = "unapproved"),
            a.copy(supplierKey = "../"), a.copy(profileKey = "other"), a.copy(manifest = "f".repeat(64)))) {
            assertFailsWith<TeachProposalStoreUnavailable> { projectTeachPromotion(invalid, registry.manifest) }
        }
    }

    @Test fun `fake rejects key collisions, divergent content and noncanonical revision paths`() {
        val one = projectTeachPromotion(a, registry.manifest)
        val two = projectTeachPromotion(b, registry.manifest)
        val fake = TeachPromotionFake(listOf(one, two))
        val first = TeachPromotionProposal(UUID.randomUUID(), one)
        assertNull(fake.lookup(first) {})
        val original = fake.createIfAbsent(first) {}
        assertEquals(original, fake.createIfAbsent(first) {})
        assertEquals(original, fake.lookup(first) {})
        for (other in listOf(two, one.copy(artifact = one.artifact + " "), one.copy(digest = two.digest),
            one.copy(revision = "../README"), one.copy(revision = "/absolute"), one.copy(revision = "%2e%2e"))) {
            assertFailsWith<TeachPromotionProviderUnavailable> { fake.createIfAbsent(first.copy(diff = other)) {} }
            assertFailsWith<TeachPromotionProviderUnavailable> { fake.lookup(first.copy(diff = other)) {} }
        }
        assertEquals(listOf(original), fake.inspect())
        assertTrue(fake.protectedBase.isEmpty())
        assertNotEquals(fake.fixtureId, TeachPromotionFake(listOf(one)).fixtureId)
    }
}
