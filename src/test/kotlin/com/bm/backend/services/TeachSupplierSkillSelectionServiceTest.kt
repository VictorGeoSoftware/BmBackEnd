package com.bm.backend.services

import com.bm.backend.models.TeachActiveSkill
import com.bm.backend.models.TeachSupplierSkillSelection.Source
import com.bm.backend.repositories.ports.TeachActiveSkillReader
import com.bm.backend.repositories.ports.TeachSkillCatalogUnavailable
import com.bm.backend.testing.*
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import kotlin.test.*

class TeachSupplierSkillSelectionServiceTest {
    private val workbooks = activationWorkbooks()
    private val a = workbooks.first()
    private val replacement = workbooks[1]
    private val registry = TeachActivationRegistry(workbooks.map { it.digest }.toSet())
    private val known = "bm-pilot-gesternova.xlsx"
    private val generic = "carga.xlsx"

    private class Reader(
        var active: TeachActiveSkill?,
        val verified: Map<String, TeachActiveSkill> = emptyMap(),
    ) : TeachActiveSkillReader {
        override val storeId = "1".repeat(32)
        val lookups = mutableListOf<Pair<String, String>>()
        var unavailable = false
        override fun verify(manifest: String) = verified
        override fun read(manifest: String, supplier: String, remainingMillis: () -> Long, checkpoint: () -> Unit): TeachActiveSkill? {
            lookups += manifest to supplier
            if (unavailable) throw TeachSkillCatalogUnavailable()
            return active
        }
    }

    private fun TeachSupplierSkillSelectionService.select(name: String, bytes: ByteArray) =
        select(name, TeachWorkbookValidator(), TeachWorkbookInspector(), bytes, bytes.size, { 15_000 }, {})

    @Test fun `both sources select the exact entry independently of the teaching pin`() {
        val reader = Reader(TeachActiveSkill(replacement.revision, "2"))
        val service = checkNotNull(TeachSupplierSkillSelectionService.mount(reader, registry))
        val marker = supplierMarkerWorkbook(markerRow(1, " GeSTERNOVA ") + markerRow(2, "gesternova"))
        val markerDigest = sha256Hex(MessageDigest.getInstance("SHA-256").digest(marker))
        assertTrue(workbooks.none { it.digest == markerDigest })
        for ((filename, bytes, source) in listOf(
            Triple(known, a.bytes, Source.Filename),
            Triple(" BM-PILOT-GESTERNOVA-20TD .XLSX", a.bytes, Source.Filename),
            Triple(known, marker, Source.Filename),
            Triple(generic, marker, Source.Content),
            Triple(generic, supplierMarkerWorkbook(sharedSupplierMarkerRow(), sharedStrings = validSupplierSharedStrings), Source.Content),
            Triple("backup-bm-pilot-gesternova.xlsx", marker, Source.Content),
        )) {
            val result = service.select(filename, bytes)
            assertEquals(source, result.source)
            assertEquals("2", result.generation)
            assertEquals(GESTERNOVA_SUPPLIER, result.revision.supplierKey)
            assertEquals(replacement.revision, result.revision.revisionId)
            assertEquals(replacement.digest, result.revision.workbookSha256)
            assertEquals(GESTERNOVA_PREVIEW_PROFILE, result.revision.profileKey)
            assertEquals("gesternova-fixed-extractor/1", result.revision.semantics)
            assertEquals(registry.manifest, result.revision.manifest)
        }
        assertEquals(List(6) { registry.manifest to GESTERNOVA_SUPPLIER }, reader.lookups)
    }

    @Test fun `unknown ambiguous conflicting and invalid evidence never query a pointer`() {
        val reader = Reader(TeachActiveSkill(a.revision, "1"))
        val service = checkNotNull(TeachSupplierSkillSelectionService.mount(reader, registry))
        fun denied(name: String, book: ByteArray, expected: TeachSupplierSelectionError) {
            assertEquals(expected, assertFailsWith<TeachSupplierSelectionConflict> { service.select(name, book) }.error)
        }
        for (name in listOf(generic, "backup-bm-pilot-gesternova.xlsx", "bm-pilot-geſternova.xlsx", "\u00a0$known")) {
            denied(name, a.bytes, TeachSupplierSelectionError.Unknown)
        }
        for (book in listOf(supplierMarkerWorkbook(sheet = "bm identity"), supplierMarkerWorkbook(markerRow(3, "gesternova")),
            supplierMarkerWorkbook(rows = """<row r="1">${markerText("C1", SYNTHETIC_SUPPLIER_MARKER)}${markerText("D1", "gesternova")}</row>"""),
            supplierMarkerWorkbook(rows = """<row r="1"><c r="A1"/>${markerText("B1", "")}</row>"""))) {
            denied(generic, book, TeachSupplierSelectionError.Unknown)
        }
        denied(known, supplierMarkerWorkbook(markerRow(1, "luz-fixture")), TeachSupplierSelectionError.Conflicting)
        val both = supplierMarkerWorkbook(markerRow(1, "gesternova") + markerRow(2, "luz-fixture"))
        for (name in listOf(known, generic)) denied(name, both, TeachSupplierSelectionError.Ambiguous)
        val label = markerText("A1", SYNTHETIC_SUPPLIER_MARKER)
        val negativeBooks = listOf(
            supplierMarkerWorkbook(markerRow(1, "otro")),
            supplierMarkerWorkbook(markerRow(1, "\u00a0gesternova")),
            supplierMarkerWorkbook(markerRow(1, "gesternova", "hidden=\"1\"")),
            supplierMarkerWorkbook(sheetAttributes = "state=\"veryHidden\""),
            supplierMarkerWorkbook(beforeRows = """<cols><col min="2" max="2" width="0"/></cols>"""),
            supplierMarkerWorkbook(beforeRows = """<sheetFormatPr zeroHeight="true"/>"""),
            supplierMarkerWorkbook(afterRows = """<mergeCells count="1"><mergeCell ref="A1:B1"/></mergeCells>"""),
            supplierMarkerWorkbook(workbookMetadata = """<bookViews><workbookView visibility="hidden"/></bookViews>"""),
            supplierMarkerWorkbook("""<row r="1">$label</row>"""),
            supplierMarkerWorkbook("""<row r="1">${markerText("A1", "BM Synthetic Supplier")}${markerText("B1", "gesternova")}</row>"""),
            supplierMarkerWorkbook("""<row r="1">$label<c r="B1" t="str"><f>"gesternova"</f><v>gesternova</v></c></row>"""),
            supplierMarkerWorkbook("""<row r="1">$label<c r="B1" t="str"><v>gesternova</v></c></row>"""),
            supplierMarkerWorkbook("""<row r="1">$label<c r="B1"><v>1</v></c></row>"""),
            supplierMarkerWorkbook("""<row r="1">$label$label${markerText("B1", "gesternova")}</row>"""),
            supplierMarkerWorkbook(markerRow(1, "gesternova") + markerRow(1, "gesternova")),
        )
        for (book in negativeBooks) denied(known, book, TeachSupplierSelectionError.InvalidEvidence)
        assertFailsWith<InvalidWorkbook> { service.select(known, "not a workbook".encodeToByteArray()) }
        assertTrue(reader.lookups.isEmpty())
    }

    @Test fun `only unique suppliers query once and absence is distinct from a corrupt catalog`() {
        val reader = Reader(null)
        val service = checkNotNull(TeachSupplierSkillSelectionService.mount(reader, registry))
        assertEquals(TeachSupplierSelectionError.NoActiveSkill, assertFailsWith<TeachSupplierSelectionConflict> {
            service.select("bm-pilot-luz-fixture.xlsx", a.bytes)
        }.error)
        assertEquals(listOf(registry.manifest to "luz-fixture"), reader.lookups)
        for (active in listOf(TeachActiveSkill("f".repeat(64), "1"), TeachActiveSkill(a.revision, "01"),
            TeachActiveSkill(a.revision, "0"), TeachActiveSkill(null, "0"))) {
            assertNull(TeachSupplierSkillSelectionService.mount(Reader(null, mapOf(GESTERNOVA_SUPPLIER to active)), registry))
            reader.active = active
            assertFailsWith<TeachSkillCatalogUnavailable> { service.select(known, a.bytes) }
        }
        reader.active = TeachActiveSkill(a.revision, "1")
        assertFailsWith<TeachSkillCatalogUnavailable> { service.select("bm-pilot-luz-fixture.xlsx", a.bytes) }
        reader.unavailable = true
        assertFailsWith<TeachSkillCatalogUnavailable> { service.select(known, a.bytes) }
        assertEquals(7, reader.lookups.size)
    }
}
