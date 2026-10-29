package com.bm.backend.services.premiumreport

import com.bm.backend.testing.PdfInspection
import com.bm.backend.testing.PremiumReportFixtures
import com.lowagie.text.Paragraph
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PremiumReportPdfTest {
    private fun section(landscape: Boolean, label: String) = PremiumReportSection { document, _, _ ->
        document.setPageSize(if (landscape) PremiumReportTheme.PAGE_LANDSCAPE else PremiumReportTheme.PAGE_PORTRAIT)
        document.newPage()
        document.add(PremiumReportBlocks.sectionBar(label))
        document.add(Paragraph("Contenido $label", PremiumReportTheme.body()))
    }

    @Test
    fun `I sections plug in with their own orientation`() {
        val pdf = PdfInspection(
            PremiumReportPdf.render(listOf(section(false, "UNO"), section(true, "DOS")), PremiumReportFixtures.dataset())
        )
        assertEquals(2, pdf.pageCount)
        assertEquals(listOf(true, false), pdf.portraitPages)
        assertTrue("Contenido UNO" in pdf.text && "Contenido DOS" in pdf.text)
    }

    @Test
    fun `a landscape first section starts on a landscape first page`() {
        val pdf = PdfInspection(
            PremiumReportPdf.render(listOf(section(true, "UNO"), section(false, "DOS")), PremiumReportFixtures.dataset())
        )
        assertEquals(listOf(false, true), pdf.portraitPages)
    }
}
