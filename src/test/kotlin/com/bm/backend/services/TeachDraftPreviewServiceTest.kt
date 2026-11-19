package com.bm.backend.services

import com.bm.backend.models.TeachDraftPreviewFrame
import com.bm.backend.models.TeachDraftPreviewOutput
import com.bm.backend.models.TeachDraftPreviewPrepared
import com.bm.backend.models.TeachDraftPreviewResult
import com.bm.backend.models.TeachDraftPreviewSelection
import com.bm.backend.testing.*
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import kotlin.test.*

class TeachDraftPreviewServiceTest {
    private val service = TeachDraftPreviewService(TeachWorkbookValidator(), TeachWorkbookInspector())
    private val source = previewWorkbook()
    private val digest = sha256(source)
    private val wireJson = Json { classDiscriminator = "kind" }

    @Test fun `prepared selection and complete ordered preview agree with the approved synthetic oracle and fit four KiB`() {
        assertEquals(PREVIEW_FIXTURE_HASH, digest)
        val frame = TeachDraftPreviewFrame(source, digest, PREVIEW_FIXTURE_PROFILE, null)
        val draft = assertIs<TeachDraftPreviewPrepared>(service.execute(frame) {}).draft
        assertEquals(2, draft.schemaVersion)
        assertEquals("gesternova", draft.supplierKey)
        assertEquals(PREVIEW_FIXTURE_REVISION, draft.revisionId)
        assertEquals(PREVIEW_FIXTURE_PROFILE, draft.profileKey)
        val selection = TeachDraftPreviewSelection(draft.supplierKey, digest, draft.revisionId)
        val preview = assertIs<TeachDraftPreviewOutput>(service.execute(frame.copy(selection = selection)) {})
        assertEquals(TeachDraftPreviewOutput(draft.profileKey, draft.supplierKey, digest, draft.revisionId, previewOracleRows()), preview)
        val payload = wireJson.encodeToString(TeachDraftPreviewResult.serializer(), preview).encodeToByteArray()
        assertTrue(payload.size < 4096, "Full preview requires ${payload.size} bytes")
        listOf(selection.copy(supplierKey = "demo-electricidad"), selection.copy(workbookSha256 = "a".repeat(64)),
            selection.copy(revisionId = "a".repeat(64))).forEach {
            assertFailsWith<TeachDraftPreviewConflict> { service.execute(frame.copy(selection = it)) {} }
        }
        assertFailsWith<TeachDraftPreviewConflict> { service.execute(frame.copy(profileKey = "demo-electricidad")) {} }
        val changed = editPreviewWorkbook { it.replace("0.12125", "0.32125") }
        assertFailsWith<TeachDraftPreviewConflict> { service.execute(frame.copy(workbook = changed, workbookSha256 = sha256(changed))) {} }
    }

    @Test fun `each semantic field contributes to the canonical revision`() {
        val rule = gesternovaPreviewRule
        assertEquals(PREVIEW_FIXTURE_REVISION, previewRevision(digest, rule))
        val head = rule.headers.first()
        val bond = rule.bindings.first()
        val tails = rule.bindings.drop(1)
        val variants = listOf(
            rule.copy(kind = "other"), rule.copy(sheet = "Otra"), rule.copy(candidateRows = rule.candidateRows.reversed()),
            rule.copy(fixedRows = rule.fixedRows.drop(1)), rule.copy(tariff = "3.0TD"), rule.copy(otherTariff = "6.1TD"),
            rule.copy(fixedType = "Fijo"), rule.copy(excludedTypes = listOf("Indexado", "Híbrido")),
            rule.copy(headers = listOf(head.copy(label = "Otra")) + rule.headers.drop(1)),
            rule.copy(headers = listOf(head.copy(a1 = "F13")) + rule.headers.drop(1)),
            rule.copy(bindings = listOf(bond.copy(term = "power")) + tails), rule.copy(bindings = listOf(bond.copy(period = "P2")) + tails),
            rule.copy(bindings = listOf(bond.copy(unit = "€/MWh")) + tails),
            rule.copy(bindings = listOf(bond.copy(header = bond.header.copy(a1 = "J14"))) + tails),
            rule.copy(bindings = listOf(bond.copy(header = bond.header.copy(label = "Otra"))) + tails),
            rule.copy(absentHeaders = rule.absentHeaders.reversed()), rule.copy(styleSha256 = "0".repeat(64)),
            rule.copy(decimalPolicy = "different"),
        )
        val revisions = variants.map { previewRevision(digest, it) } + previewRevision("a".repeat(64), rule) + PREVIEW_FIXTURE_REVISION
        assertEquals(revisions.size, revisions.toSet().size)
    }

    @Test fun `numeric data and proposal names come from cells and excluded rows and surplus values are not interpreted`() {
        val changed = editPreviewWorkbook { xml -> xml.replace("0.12125", "999999.999999999999").replace("0.07123", "0.000000000001")
            .replace("Oferta sint&#233;tica A", "Otra propuesta sintética").replace("0.0427", "9.99").replace("0.0311", "9.99")
            .replace("0.0711</v>", "9.99</v>").replace("<v>0.06432</v>", "<v>0.6400</v>")
            .replace("<c r=\"Q16\"", "<c r=\"L16\"/><c r=\"M16\"/><c r=\"Q16\"") }
        val result = service.extract(changed) {}
        assertEquals(listOf("Otra propuesta sintética", "Oferta sintética B", "Oferta sintética C"), result.map { it.proposal })
        assertEquals(listOf("999999.999999999999", "0.000000000001", "0.02456"), result.first().prices.map { it.decimal })
        assertEquals(listOf("0", "0.6400", "0.02234"), result[1].prices.map { it.decimal })
        val visibleIndexedWithFormula = editPreviewWorkbook {
            it.replace("r=\"15\" hidden=\"1\"", "r=\"15\"").replace("<v>0.0311</v>", "<f>1+1</f><v>2</v>")
        }
        assertEquals(previewOracleRows(), service.extract(visibleIndexedWithFormula) {})
    }

    @Test fun `independent structure and semantics reject visible conflicts rather than returning partial rows`() {
        val replacements = listOf(
            "TEA_P1 &#8364;/kWh" to "TEA_P2 &#8364;/kWh",
            "TP_P2 (&#8364;/kWdia)" to "TP_P2 (&#8364;/kWh)",
            "<row r=\"14\">" to "<row r=\"14\" hidden=\"true\">",
            "<row r=\"17\">" to "<row r=\"17\" ht=\"0\">",
            "<sheetData>" to "<cols><col min=\"11\" max=\"11\" hidden=\"1\"/></cols><sheetData>",
            "<sheetData>" to "<cols><col min=\"11\" max=\"12\"/><col min=\"12\" max=\"13\"/></cols><sheetData>",
            "</sheetData>" to "</sheetData><mergeCells><mergeCell ref=\"K14:L14\"/></mergeCells>",
            "<c r=\"K16\" t=\"n\"><v>0.12125</v></c>" to "<c r=\"K16\"/><c r=\"K16\"/>",
            "<c r=\"K16\" t=\"n\"><v>0.12125</v></c>" to "",
            "<v>0.12125</v>" to "<f>1+1</f><v>2</v>",
            "<v>0.12125</v>" to "<v/>",
            "<c r=\"K16\" t=\"n\"><v>0.12125</v></c>" to "<c r=\"K16\" t=\"inlineStr\"><is><t>0,12</t></is></c>",
            "<c r=\"K16\" t=\"n\">" to "<c r=\"K16\" t=\"n\" s=\"1\">",
            "<c r=\"Q16\"" to "<c r=\"L16\"><v>0.1</v></c><c r=\"Q16\"",
            "Oferta sint&#233;tica B" to "Oferta sint&#233;tica A",
            "Precio por Periodos" to "Tipo desconocido",
        ) + listOf("-1", "+1", "1e-1", "1000000", "0.1234567890123").map { "0.12125" to it }
        for ((target, value) in replacements) assertFailsWith<TeachDraftPreviewConflict>("replacement: $target → $value") {
            service.extract(editPreviewWorkbook { it.replaceFirst(target, value) }) {}
        }
        assertFailsWith<TeachDraftPreviewConflict> {
            service.extract(editPreviewWorkbook("xl/styles.xml") { it.replace("numFmtId=\"0\"", "numFmtId=\"14\"") }) {}
        }
        assertFailsWith<TeachDraftPreviewConflict> {
            service.extract(editPreviewWorkbook("xl/styles.xml") { it.replace("numFmtId=\"0\"", "numFmtId=\"9\"") }) {}
        }
        assertFailsWith<TeachDraftPreviewConflict> {
            service.extract(editPreviewWorkbook("xl/workbook.xml") { it.replace("state=\"visible\"", "state=\"hidden\"") }) {}
        }
        assertFailsWith<WorkbookBudgetExceeded> {
            service.extract(editPreviewWorkbook { it.replace("0.12125", "1".repeat(65)) }) {}
        }
    }
}
