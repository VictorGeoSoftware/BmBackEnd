package com.bm.backend.services

import com.bm.backend.models.*
import com.bm.backend.testing.activationWorkbooks
import com.bm.backend.testing.previewOracleRows
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import kotlin.test.*

class TeachSkillActivationReceiptTest {
    private val key = ByteArray(32) { it.toByte() }
    private val fixture = "a".repeat(32)
    private val manifest = "b".repeat(64)
    private val workbook = activationWorkbooks().first()
    private val claim = TeachActivationClaim("synthetic-author", GESTERNOVA_SUPPLIER, GESTERNOVA_PREVIEW_PROFILE,
        workbook.digest, workbook.revision, TeachActiveSkill(null, "0"), "c".repeat(32), 1800000000, 1800000300)
    private val codec = TeachActivationReceipts(key, fixture, manifest)
    private val wire = Json { classDiscriminator = "kind" }

    @Test fun `receipt authenticates canonical context and bounds without relying on workbook matching`() {
        val proof = codec.issue(claim)
        assertEquals(claim, codec.verify(proof))
        assertFailsWith<TeachActivationConflict> { TeachActivationReceipts(key, "d".repeat(32), manifest).verify(proof) }
        assertFailsWith<TeachActivationConflict> { TeachActivationReceipts(key, fixture, "e".repeat(64)).verify(proof) }
        assertFailsWith<TeachActivationConflict> { TeachActivationReceipts(ByteArray(32), fixture, manifest).verify(proof) }
        for (invalid in listOf(proof + "=", proof + ".", "é", "x".repeat(1537), proof.substringAfter('.'))) {
            assertFailsWith<TeachActivationConflict> { codec.verify(invalid) }
        }
        for (invalid in listOf(claim.copy(actor = "a".repeat(65)), claim.copy(expected = TeachActiveSkill(null, "1")),
            claim.copy(expected = TeachActiveSkill(workbook.revision, "9223372036854775808")),
            claim.copy(expected = TeachActiveSkill(workbook.revision, "+1")), claim.copy(nonce = "C".repeat(32)),
            claim.copy(expiresAt = claim.expiresAt + 1), claim.copy(workbookSha256 = "C".repeat(64)))) {
            assertFailsWith<TeachActivationConflict> { codec.verify(codec.issue(invalid)) }
        }
    }

    @Test fun `complete preview fits the 4 KiB wire at maximum real field lengths`() {
        val rows = previewOracleRows().mapIndexed { i, row ->
            row.copy(proposal = "\\".repeat(95) + i, prices = row.prices.map { it.copy(decimal = "999999.999999999999") })
        }
        val output = TeachDraftPreviewOutput(GESTERNOVA_PREVIEW_PROFILE, GESTERNOVA_SUPPLIER, workbook.digest, workbook.revision, rows)
        val full = claim.copy(actor = "q".repeat(64), expected = TeachActiveSkill(workbook.revision, Long.MAX_VALUE.toString()),
            issuedAt = 9007199254740691L, expiresAt = 9007199254740991L)
        val receipt = codec.issue(full)
        assertTrue(receipt.length <= 1536)
        assertEquals(full, codec.verify(receipt))
        val json = wire.encodeToString(TeachQaPilotPreview.serializer(),
            TeachQaPilotPreview(output, full.expected, full.expiresAt, receipt))
        assertTrue(json.encodeToByteArray().size <= 4096)
    }
}
