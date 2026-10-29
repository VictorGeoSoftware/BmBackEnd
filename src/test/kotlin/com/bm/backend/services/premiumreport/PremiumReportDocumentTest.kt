package com.bm.backend.services.premiumreport

import com.bm.backend.models.ReportDataset
import com.bm.backend.testing.PdfInspection
import com.bm.backend.testing.PremiumReportFixtures as F
import com.lowagie.text.pdf.PdfReader
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PremiumReportDocumentTest {
    private val renderer = PdfPremiumReportRenderer(F.CLOCK)
    private val footer = Regex("Página (\\d+) de (\\d+)")

    private fun render(dataset: ReportDataset) = PdfInspection(renderer.render(dataset))

    /** First page index (1-based) whose text contains [marker], or -1. */
    private fun PdfInspection.firstPage(marker: String) = pageTexts.indexOfFirst { it.contains(marker) } + 1

    private fun assertFooters(pdf: PdfInspection) {
        val n = pdf.pageCount
        pdf.pageTexts.forEachIndexed { i, text ->
            val matches = footer.findAll(text).toList()
            if (i == 0) {
                assertFalse(text.contains("Página"), "cover must have no footer")
            } else {
                assertEquals(1, matches.size, "page ${i + 1}: $text")
                assertEquals(listOf("${i + 1}", "$n"), matches.single().groupValues.drop(1), "page ${i + 1}")
            }
        }
    }

    private fun alone(section: PremiumReportSection, dataset: ReportDataset) =
        PdfInspection(PremiumReportPdf.render(listOf(section), dataset)).pageCount

    @Test
    fun `A and B sections in Q1 order with increasing start pages, company last`() {
        val d = F.documentDataset()
        val pdf = render(d)
        assertTrue(pdf.isPdf)
        assertTrue(pdf.pageTexts[0].contains(PremiumReportTexts.TITLE))
        val starts = listOf(PremiumActiveTexts.SECTION, PremiumReactiveTexts.SECTION, PremiumDemandTexts.SECTION,
            d.comparison.title, PremiumCompanyTexts.DATA_PROTECTION_SECTION).map { pdf.firstPage(it) }
        assertTrue(starts.all { it > 1 }, "$starts")
        assertEquals(starts.sorted().distinct(), starts, "$starts")
        assertEquals(pdf.pageCount, starts.last())
    }

    @Test
    fun `C orientation and page count equal the sum of the sections rendered alone`() {
        val d = F.documentDataset()
        val pdf = render(d)
        val comparison = pdf.firstPage(d.comparison.title)
        val company = pdf.firstPage(PremiumCompanyTexts.DATA_PROTECTION_SECTION)
        pdf.portraitPages.forEachIndexed { i, portrait ->
            assertEquals((i + 1) !in comparison until company, portrait, "page ${i + 1}")
        }
        val expected = renderer.defaultSections(d).sumOf { alone(it, d) }
        assertEquals(expected, pdf.pageCount)
    }

    @Test
    fun `D footer F1 on every page but the cover with exact numbers`() {
        listOf(F.documentDataset(), F.documentUnavailableDataset(), F.documentDataset(offers = 15),
            F.documentDataset(F.documentActiveOnlyRows())).forEach { assertFooters(render(it)) }
    }

    @Test
    fun `E only embedded Liberation Sans, neutral metadata`() {
        val bytes = renderer.render(F.documentDataset())
        val pdf = PdfInspection(bytes)
        assertTrue(pdf.fonts.isNotEmpty())
        pdf.fonts.forEach { (name, embedded) ->
            assertTrue(embedded, name)
            assertTrue(Regex("/?[A-Z]{6}\\+LiberationSans(-Bold)?").matches(name), name)
        }
        assertEquals(PremiumReportTexts.TITLE, pdf.info["Title"])
        assertEquals("BM", pdf.info["Author"])
        assertEquals("BM", pdf.info["Creator"])
        val metadata = pdf.info.values.joinToString(" ") + String(PdfReader(bytes).metadata ?: ByteArray(0), Charsets.UTF_8)
        listOf(F.CUPS, F.JOB_ID, "Cliente Prueba", "Calle Ficticia").forEach { assertFalse(metadata.contains(it), it) }
    }

    @Test
    fun `F 15 offers give 3 comparison pages and the long history renders within budget`() {
        val d = F.documentDataset(F.documentLongRows(), offers = 15)
        assertTrue(d.history.intervals.size >= 120)
        renderer.render(d) // warm-up (fonts, classes)
        val started = System.nanoTime()
        val bytes = renderer.render(d)
        val ms = (System.nanoTime() - started) / 1_000_000
        println("report-document long fixture: ${ms} ms, ${bytes.size} bytes")
        val pdf = PdfInspection(bytes)
        assertEquals(3, pdf.portraitPages.count { !it })
        assertEquals(3, pdf.pageTexts.count { it.contains(d.comparison.title) })
        assertTrue(ms < 15_000, "$ms ms")
        assertTrue(bytes.size < 3 * 1024 * 1024, "${bytes.size} bytes")
        assertFooters(pdf)
    }

    @Test
    fun `G a failing section throws and yields no bytes, a later render succeeds`() {
        val failing = PdfPremiumReportRenderer(F.CLOCK) { d ->
            PdfPremiumReportRenderer(F.CLOCK).defaultSections(d).take(2) + PremiumReportSection { _, _, _ -> error("boom") }
        }
        assertFailsWith<IllegalStateException> { failing.render(F.documentDataset()) }
        assertTrue(render(F.documentDataset()).isPdf)
    }

    @Test
    fun `J determinism - same input and clock give the same pages`() {
        val a = render(F.documentDataset())
        val b = render(F.documentDataset())
        assertEquals(a.pageTexts, b.pageTexts)
        assertEquals(a.portraitPages, b.portraitPages)
    }

    // ---- Q4 b: omit reactive/demand only when the history has no readings of that family (N1) ----

    @Test
    fun `Q4 both families absent - both sections omitted, numbering still exact`() {
        val pdf = render(F.documentDataset(F.documentActiveOnlyRows()))
        assertEquals(0, pdf.firstPage(PremiumReactiveTexts.SECTION))
        assertEquals(0, pdf.firstPage(PremiumDemandTexts.SECTION))
        assertFalse(pdf.text.contains(PremiumReactiveTexts.NO_REACTIVE_ROWS))
        assertFalse(pdf.text.contains(PremiumDemandTexts.NO_DEMAND_ROWS))
        assertTrue(pdf.firstPage(PremiumActiveTexts.SECTION) == 2)
        assertFooters(pdf)
    }

    @Test
    fun `Q4 one family absent - only that section omitted`() {
        val noReactive = render(F.documentDataset(F.reactiveMissingRows()))
        assertEquals(0, noReactive.firstPage(PremiumReactiveTexts.SECTION))
        assertTrue(noReactive.firstPage(PremiumDemandTexts.SECTION) > 0)
        assertFooters(noReactive)
        val noDemand = render(F.documentDataset(F.demandNoRows()))
        assertTrue(noDemand.firstPage(PremiumReactiveTexts.SECTION) > 0)
        assertEquals(0, noDemand.firstPage(PremiumDemandTexts.SECTION))
        assertFooters(noDemand)
    }

    @Test
    fun `Q4 kept - N2 unavailable history shows both disclosures`() {
        val pdf = render(F.documentUnavailableDataset())
        assertTrue(pdf.firstPage(PremiumReactiveTexts.SECTION) > 0)
        assertTrue(pdf.firstPage(PremiumDemandTexts.SECTION) > 0)
        assertTrue(pdf.text.contains(PremiumReactiveTexts.REACTIVE_UNAVAILABLE))
        assertTrue(pdf.text.contains(PremiumDemandTexts.DEMAND_UNAVAILABLE))
        assertFooters(pdf)
    }

    @Test
    fun `Q4 kept - readings only outside the window (C2 and D) keep the section`() {
        assertTrue(render(F.documentDataset(F.reactiveOutsideRows())).firstPage(PremiumReactiveTexts.SECTION) > 0)
        assertTrue(render(F.documentDataset(F.demandOutsideRows())).firstPage(PremiumDemandTexts.SECTION) > 0)
    }
}
