package com.bm.backend.services.premiumreport

import com.bm.backend.models.ReportDataset
import com.lowagie.text.Document
import com.lowagie.text.pdf.PdfWriter
import java.io.ByteArrayOutputStream

/** Opens an A4 document with neutral metadata (no customer identity), writes the sections in order and closes it. */
object PremiumReportPdf {
    fun render(sections: List<PremiumReportSection>, dataset: ReportDataset): ByteArray {
        val output = ByteArrayOutputStream()
        val document = Document(
            PremiumReportTheme.PAGE_PORTRAIT,
            PremiumReportTheme.MARGIN_HORIZONTAL, PremiumReportTheme.MARGIN_HORIZONTAL,
            PremiumReportTheme.MARGIN_VERTICAL, PremiumReportTheme.MARGIN_VERTICAL
        )
        val writer = PdfWriter.getInstance(document, output)
        document.addTitle(PremiumReportTexts.TITLE)
        document.addAuthor(PremiumReportTexts.AUTHOR)
        document.addCreator(PremiumReportTexts.AUTHOR)
        document.open()
        try {
            sections.forEach { it.write(document, writer, dataset) }
        } finally {
            if (document.isOpen) runCatching { document.close() }
        }
        return output.toByteArray()
    }
}
