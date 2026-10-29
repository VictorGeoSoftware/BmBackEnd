package com.bm.backend.services.premiumreport

import com.bm.backend.models.ComparatorReportPdfRequest
import com.bm.backend.services.ComparatorReportPdfService
import com.bm.backend.services.ComparatorReportPdfTexts
import com.bm.backend.testing.PdfInspection
import com.bm.backend.testing.PremiumReportFixtures
import com.bm.backend.testing.ReportComparisonFixtures as F
import com.lowagie.text.pdf.PdfReader
import com.lowagie.text.pdf.parser.PdfTextExtractor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Locale

class PremiumComparisonSectionTest {

    private val scenarios: Map<String, Pair<ComparatorReportPdfRequest, Int>> = linkedMapOf(
        "A" to (F.pageRequest() to 1),
        "B" to (F.pageScenarioB() to 1),
        "C" to (F.pageScenarioC() to 1),
        "D" to (F.pageScenarioD() to 1),
        "E" to (F.pageScenarioOffers(7) to 1),
        "F8" to (F.pageScenarioOffers(8) to 2),
        "F15" to (F.pageScenarioOffers(15) to 3),
        "G" to (F.pageScenarioG() to 1),
        "H" to (F.pageScenarioH() to 1),
        "Q3" to (F.pageScenarioShortItems() to 1)
    )

    private fun render(c: ComparatorReportPdfRequest): ByteArray =
        PremiumReportPdf.render(listOf(PremiumComparisonSection()), PremiumReportFixtures.dataset().copy(comparison = c))

    private fun pageTexts(bytes: ByteArray): List<String> {
        val reader = PdfReader(bytes)
        val extractor = PdfTextExtractor(reader)
        return (1..reader.numberOfPages).map { normalise(extractor.getTextFromPage(it)) }
    }

    private fun normalise(s: String) = s.replace('\u00A0', ' ').replace(Regex("\\s+"), " ").trim()

    private fun compact(s: String) = s.replace(Regex("\\s+"), "")

    private fun supplyBlock(c: ComparatorReportPdfRequest) = normalise(listOf(
        ComparatorReportPdfTexts.SUPPLY_HOLDER_LABEL, c.supplyHolder.uppercase(Locale("es", "ES")),
        ComparatorReportPdfTexts.SUPPLY_ADDRESS_LABEL, c.supplyAddress.uppercase(Locale("es", "ES")),
        ComparatorReportPdfTexts.CUPS_LABEL, c.cups
    ).joinToString(" "))

    @Test
    fun `page by page text parity with the Basic PDF of the same request`() {
        scenarios.forEach { (name, pair) ->
            val (c, pages) = pair
            val basic = pageTexts(ComparatorReportPdfService().generate(c))
            val premium = pageTexts(render(c))
            assertEquals(pages, basic.size, "$name basic pages")
            assertEquals(pages, premium.size, "$name premium pages")
            basic.indices.forEach { k ->
                // The extractor joins adjacent cells with or without a space depending on glyph positions, so the
                // ordered character sequence is compared with all whitespace removed: any figure, label, sign or
                // order difference still fails.
                val block = compact(supplyBlock(c))
                val basicCompact = compact(basic[k])
                assertTrue(basicCompact.contains(block), "$name page ${k + 1}: supply block found in Basic")
                assertEquals(basicCompact.replaceFirst(block, ""), compact(premium[k]), "$name page ${k + 1}")
                assertFalse(premium[k].contains(ComparatorReportPdfTexts.SUPPLY_HOLDER_LABEL), "$name no supply block (Q2 a)")
                assertFalse(premium[k].contains(c.cups), "$name no CUPS (Q2 a)")
            }
        }
    }

    @Test
    fun `every page repeats the section bar header and current column with Basic pagination`() {
        listOf(F.pageScenarioOffers(8) to listOf(7, 1), F.pageScenarioOffers(15) to listOf(7, 7, 1)).forEach { (c, split) ->
            val texts = pageTexts(render(c))
            val model = ComparisonTableModel.pages(c)
            assertEquals(split, model.map { it.offers.size })
            assertEquals(c.proposals.chunked(7), model.map { it.offers })
            texts.forEachIndexed { k, t ->
                assertTrue(t.startsWith(normalise(c.title) + " " + c.tariffName + " DATOS DISTRIBUIDORA CONDICIONES ACTUALES"), "page ${k + 1}")
                assertTrue(t.contains("0.100000 €/kW año"))
                model[k].offers.forEach { assertTrue(t.contains(it.title)) }
            }
        }
    }

    @Test
    fun `landscape pages embedded Liberation Sans only and deterministic`() {
        scenarios.values.forEach { (c, pages) ->
            val bytes = render(c)
            val pdf = PdfInspection(bytes)
            assertEquals(List(pages) { false }, pdf.portraitPages)
            assertTrue(pdf.fonts.isNotEmpty())
            pdf.fonts.forEach { (font, embedded) ->
                assertTrue(font.contains("LiberationSans"), font)
                assertTrue(embedded, font)
            }
            assertEquals(pageTexts(bytes), pageTexts(render(c)))
            assertEquals(ComparisonTableModel.pages(c), ComparisonTableModel.pages(c))
        }
    }

    @Test
    fun `long strings are complete in the text`() {
        val c = F.pageScenarioG()
        val text = PdfInspection(render(c)).text
        assertTrue(text.contains(c.proposals[0].title))
        assertTrue(text.contains(c.tariffName))
    }

    @Test
    fun `source is dataset comparison only`() {
        val base = PremiumReportFixtures.dataset().copy(comparison = F.pageRequest())
        val changed = base.copy(report = base.report.copy(proposals = base.report.proposals.map { it.copy(totalAnnualPrice = 1.0, proposalTitle = "Otra") }))
        val section = listOf(PremiumComparisonSection())
        assertEquals(pageTexts(PremiumReportPdf.render(section, base)), pageTexts(PremiumReportPdf.render(section, changed)))
        assertEquals(PdfInspection(PremiumReportPdf.render(section, base)).pageCount, PdfInspection(PremiumReportPdf.render(section, changed)).pageCount)
    }

    @Test
    fun `forbidden vocabulary absent in every scenario`() {
        val forbidden = listOf("recomend", "aconsej", "mejor oferta", "penaliz", "recargo", "optimi", "comercializadora actual", "proveedor actual")
        scenarios.values.forEach { (c, _) ->
            val text = PdfInspection(render(c)).text.lowercase()
            forbidden.forEach { assertFalse(text.contains(it), it) }
        }
    }
}
