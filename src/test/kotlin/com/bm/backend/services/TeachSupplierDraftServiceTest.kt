package com.bm.backend.services

import com.bm.backend.models.TeachClarificationCandidate
import com.bm.backend.models.TeachClarificationCitation
import com.bm.backend.models.TeachClarificationResult
import com.bm.backend.models.TeachSupplierColumnChoice
import com.bm.backend.models.TeachSupplierColumnChoice.B
import com.bm.backend.models.TeachSupplierColumnChoice.C
import com.bm.backend.models.TeachSupplierDraftFrame
import com.bm.backend.testing.*
import org.junit.jupiter.api.Test
import kotlin.test.*

class TeachSupplierDraftServiceTest {
    private val workbook = supplierDraftWorkbook()
    private val digest = sha256(workbook)
    private val validator = TeachWorkbookValidator()
    private val inspector = TeachWorkbookInspector()

    private fun service(adapter: TeachSupplierDraftAdapter) = TeachSupplierDraftService(validator, inspector, adapter)

    private fun frame(choice: TeachSupplierColumnChoice? = null, prior: String? = null, workbook: ByteArray = this.workbook,
                      expected: String? = choice?.let { sha256(workbook) }, supplier: String? = choice?.let { "demo-electricidad" }) =
        TeachSupplierDraftFrame(workbook, sha256(workbook), expected, supplier, prior, choice)

    @Test fun `verified ordinal descriptors and closed choice reach adapter and content identity is deterministic in both directions`() {
        val choices = mutableListOf<TeachSupplierColumnChoice>()
        val service = service { candidates, choice ->
            assertEquals(listOf(TeachClarificationCandidate(0, "B2"), TeachClarificationCandidate(0, "C2")), candidates)
            choices += choice
            val index = fixedTeachSupplierDraftAdapter.select(candidates, choice)
            (candidates as MutableList).clear() // The adapter cannot change the service's own mapping.
            index
        }
        val headers = listOf(TeachClarificationCitation("Tarifas", "B2"), TeachClarificationCitation("Tarifas", "C2"))
        assertEquals(TeachClarificationResult.Question(digest, headers), service.question(frame()) {})
        assertTrue(choices.isEmpty())
        val b = service.draft(frame(B)) {}
        val c = service.draft(frame(C, b.revisionId)) {}
        for ((draft, header) in listOf(b, c).zip(headers)) {
            assertEquals(1, draft.schemaVersion)
            assertEquals("demo-electricidad", draft.supplierKey)
            assertEquals(digest, draft.workbookSha256)
            assertEquals("fixed_price_column", draft.rule.kind)
            assertEquals(header, draft.rule.header)
            assertEquals(supplierDraftRevision(digest, header.a1), draft.revisionId)
        }
        assertNotEquals(b.revisionId, c.revisionId)
        assertEquals(b, service.draft(frame(B, c.revisionId)) {})
        assertEquals(b, service.draft(frame(B)) {})
        assertEquals(c, service.draft(frame(C)) {})
        assertEquals(listOf(B, C, B, B, C), choices)
    }

    @Test fun `unapproved bytes mismatched continuity unknown supplier and no change never reach the port`() {
        val service = service { _, _ -> error("Unexpected dispatch") }
        val other = clarificationWorkbook()
        assertFailsWith<TeachSupplierDraftConflict> { service.question(frame(workbook = other)) {} }
        assertFailsWith<TeachSupplierDraftConflict> { service.draft(frame(B, workbook = other)) {} }
        assertFailsWith<TeachSupplierDraftConflict> { service.draft(frame(B, expected = sha256(other))) {} }
        assertFailsWith<TeachSupplierDraftConflict> { service.draft(frame(C, prior = "a".repeat(64))) {} }
        assertFailsWith<InvalidTeachSupplierDraft> { service.draft(frame(C, prior = supplierDraftRevision(digest, "C2"))) {} }
        assertFailsWith<InvalidTeachSupplierDraft> { service.draft(frame(C, supplier = null)) {} }
        assertFailsWith<InvalidTeachSupplierDraft> { service.draft(frame(C, supplier = "otro")) {} }
    }

    @Test fun `rejects invalid indices and a valid index for the other column`() {
        for (index in listOf(-1, 2, Int.MAX_VALUE, 1)) {
            assertFailsWith<IllegalStateException> { service { _, _ -> index }.draft(frame(B)) {} }
        }
    }

    @Test fun `shared selector uses only visible literals and fails closed on uncertain visibility`() {
        fun headers(bytes: ByteArray) = verifiedTeachFixedPriceHeaders(validator, inspector, bytes) {}?.map { it.citation.a1 }
        val hiddenThird = clarificationWorkbook(3) { it.clarificationColumns("min=\"4\" max=\"4\" hidden=\"1\"") }
        assertEquals(listOf("B2", "C2"), headers(hiddenThird))
        val hiddenC = clarificationWorkbook { it.clarificationColumns("min=\"3\" max=\"3\" hidden=\"1\"") }
        assertNull(headers(hiddenC))
        val formulaC = clarificationWorkbook {
            it.replace("""<c r="C2" t="str"><v>Precio fijo</v></c>""", """<c r="C2" t="str"><f>1</f><v>Precio fijo</v></c>""")
        }
        assertNull(headers(formulaC))
        val unknown = clarificationWorkbook { it.clarificationColumns("min=\"3\" max=\"3\" hidden=\"unknown\"") }
        assertNull(headers(unknown))
        assertNull(headers(clarificationWorkbook(3)))
    }
}
