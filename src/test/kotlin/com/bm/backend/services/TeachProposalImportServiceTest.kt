package com.bm.backend.services

import com.bm.backend.models.*
import com.bm.backend.repositories.ports.TeachActiveSkillReader
import com.bm.backend.repositories.ports.TeachProposalImportStore
import com.bm.backend.repositories.ports.TeachSkillCatalogUnavailable
import com.bm.backend.testing.*
import java.security.MessageDigest
import kotlin.test.*

class TeachProposalImportServiceTest {
    private val books = activationWorkbooks()
    private val registry = TeachActivationRegistry(books.map { it.digest }.toSet())
    private val validator = TeachWorkbookValidator()
    private val inspector = TeachWorkbookInspector()
    private val bytes = importWorkbook()
    private val catalogId = "a".repeat(32)
    private var active: TeachActiveSkill? = TeachActiveSkill(books.first().revision, "1")
    private var lookups = 0
    private val reader = object : TeachActiveSkillReader {
        override val storeId = catalogId
        override fun verify(manifest: String): Map<String, TeachActiveSkill> = emptyMap()
        override fun read(manifest: String, supplier: String, remainingMillis: () -> Long, checkpoint: () -> Unit): TeachActiveSkill? {
            assertEquals(registry.manifest, manifest)
            lookups++
            return active
        }
    }
    private val selector = assertNotNull(TeachSupplierSkillSelectionService.mount(reader, registry))
    private val expectedIdentity = TeachProposalImportIdentity("b".repeat(32), catalogId, registry.manifest)
    private fun store(descriptor: TeachProposalImportIdentity = expectedIdentity) = object : TeachProposalImportStore {
        override val identity = descriptor
        override fun verify() = Unit
        override fun exists(attempt: TeachProposalImportAttempt, remaining: () -> Long, checkpoint: () -> Unit) = false
        override fun record(attempt: TeachProposalImportAttempt, pending: TeachProposalImportPending?, remaining: () -> Long,
                            checkpoint: () -> Unit): TeachProposalImportReceipt = error("prepare wrote")
        override fun list(actor: String, before: Long?, remaining: () -> Long, checkpoint: () -> Unit): TeachProposalImportPage = error("prepare listed")
    }
    private fun prepare(service: TeachProposalImportService, workbook: ByteArray = bytes, name: String = "bm-pilot-gesternova.xlsx") =
        service.prepare(service.attempt("synthetic-author", "f".repeat(32), name, workbook, workbook.size),
            name, workbook, workbook.size, validator, inspector, { 15_000 }, {})

    @Test fun `second oracle is exact in both paths and selected A and B artifacts reach the applicator`() {
        val observed = mutableListOf<TeachRegisteredSkillRevision>()
        val service = assertNotNull(TeachProposalImportService.mount(selector, store()) { revision, shape, check ->
            observed += revision
            applyTeachPriceRule(revision.rule, shape, check)
        })
        val resultA = assertNotNull(prepare(service))
        assertEquals(TeachSupplierSkillSelection.Source.Filename, resultA.selection.source)
        active = TeachActiveSkill(books[1].revision, "2")
        val resultB = assertNotNull(prepare(service, markedImportWorkbook(), "carga.xlsx"))
        assertEquals(TeachSupplierSkillSelection.Source.Content, resultB.selection.source)
        assertEquals(2, lookups)
        assertEquals(books.take(2).map { registry.exact(GESTERNOVA_SUPPLIER, it.revision) }, observed)
        assertEquals(importOracleRows(), resultA.rows)
        assertEquals(importOracleRows(), resultB.rows)
        val advisoryDimension = editImportWorkbook { it.replace("C14:AE20", "A14:AE20") }
        assertEquals(importOracleRows(), assertNotNull(prepare(service, advisoryDimension)).rows)
        val digest = sha256Hex(MessageDigest.getInstance("SHA-256").digest(bytes))
        assertFalse(registry.containsPin(digest))
        val preview = TeachDraftPreviewService(validator, inspector)
        assertFailsWith<TeachDraftPreviewConflict> { preview.execute(TeachDraftPreviewFrame(bytes, digest, GESTERNOVA_PREVIEW_PROFILE, null)) {} }
        assertFailsWith<TeachDraftPreviewConflict> { inspector.inspectForDraftPreview(validator, markedImportWorkbook()) {} }
    }

    @Test fun `identity validity precedes shape and no incompatible form is silently omitted`() {
        val service = assertNotNull(TeachProposalImportService.mount(selector, store()))
        for ((bytes, name, reason) in listOf(
            Triple(incompatibleImportWorkbook(), "carga.xlsx", TeachSupplierSelectionError.Unknown),
            Triple(markedImportWorkbook(markerRow(1, "luz-fixture")), "bm-pilot-gesternova.xlsx", TeachSupplierSelectionError.Conflicting),
            Triple(markedImportWorkbook(markerRow(1, "gesternova") + markerRow(2, "luz-fixture")), "carga.xlsx", TeachSupplierSelectionError.Ambiguous),
            Triple(markedImportWorkbook(markerRow(1, "gesternova", "hidden=\"1\"")), "bm-pilot-gesternova.xlsx", TeachSupplierSelectionError.InvalidEvidence),
        )) assertEquals(reason, assertFailsWith<TeachSupplierSelectionConflict> { prepare(service, bytes, name) }.error)
        assertEquals(0, lookups)
        val incompatible = listOf(
            incompatibleImportWorkbook(),
            editImportWorkbook { it.replace("TEA_P1 &#8364;/kWh", "TEA_P1 &#8364;/MWh") },
            editImportWorkbook { it.replace("""<row r="18">""", """<row r="18" hidden="1">""") },
            editImportWorkbook { it.replace("""<c r="K16"""", """<c r="K16" s="1"""") },
            editImportWorkbook { it.replace("</row></sheetData>", "</row><row r=\"21\"/></sheetData>") },
            editImportWorkbook { it.replace("0.151234567891", "0.1512345678911") },
            editImportWorkbook { it.replace("0.151234567891", "1.51234567891E-1") },
        )
        incompatible.forEach { assertFailsWith<TeachDraftPreviewConflict> { prepare(service, it) } }
        assertEquals(incompatible.size, lookups)
        active = null
        assertEquals(TeachSupplierSelectionError.NoActiveSkill, assertFailsWith<TeachSupplierSelectionConflict> { prepare(service) }.error)
        active = TeachActiveSkill("f".repeat(64), "1")
        assertFailsWith<TeachSkillCatalogUnavailable> { prepare(service) }
        for (invalid in listOf(expectedIdentity.copy(schemaVersion = 3), expectedIdentity.copy(environment = "prod"),
            expectedIdentity.copy(catalogId = "c".repeat(32)), expectedIdentity.copy(manifest = "f".repeat(64)))) {
            assertNull(TeachProposalImportService.mount(selector, store(invalid)))
        }
    }
}
