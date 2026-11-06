package com.bm.backend.services.premiumreport

import com.lowagie.text.Document
import com.lowagie.text.pdf.PdfPageEventHelper
import com.lowagie.text.pdf.PdfTemplate
import com.lowagie.text.pdf.PdfWriter

/**
 * F1 footer `Página {n} de {total}` (small grey, centred in the bottom margin) on every page except the cover;
 * the cover counts as page 1 (user answer Q3 a). `{total}` is a shared template filled when the document closes.
 * Create one instance per render: it holds per-document state.
 */
class PremiumPageFooter : PdfPageEventHelper() {
    private var total: PdfTemplate? = null

    override fun onOpenDocument(writer: PdfWriter, document: Document) {
        total = writer.directContent.createTemplate(TOTAL_WIDTH, TOTAL_HEIGHT)
    }

    override fun onEndPage(writer: PdfWriter, document: Document) {
        val page = writer.pageNumber
        if (page <= 1) return
        val template = total ?: return
        val font = PremiumReportTheme.regularBaseFont()
        val size = PremiumReportTheme.SIZE_NOTE
        val prefix = PremiumDocumentTexts.pagePrefix(page)
        val prefixWidth = font.getWidthPoint(prefix, size)
        val pageBox = document.pageSize
        val x = (pageBox.left + pageBox.right) / 2f - (prefixWidth + font.getWidthPoint(TOTAL_SAMPLE, size)) / 2f
        val cb = writer.directContent
        cb.saveState()
        cb.beginText()
        cb.setFontAndSize(font, size)
        cb.setColorFill(PremiumReportTheme.COLOR_MUTED)
        cb.setTextMatrix(x, BASELINE)
        cb.showText(prefix)
        cb.endText()
        cb.addTemplate(template, x + prefixWidth, BASELINE)
        cb.restoreState()
    }

    override fun onCloseDocument(writer: PdfWriter, document: Document) {
        val template = total ?: return
        template.beginText()
        template.setFontAndSize(PremiumReportTheme.regularBaseFont(), PremiumReportTheme.SIZE_NOTE)
        template.setColorFill(PremiumReportTheme.COLOR_MUTED)
        template.setTextMatrix(0f, 0f)
        template.showText((writer.pageNumber - 1).toString())
        template.endText()
    }

    private companion object {
        /** Inside the 36 pt bottom margin, below any section content. */
        const val BASELINE = 18f
        const val TOTAL_WIDTH = 40f
        const val TOTAL_HEIGHT = 12f
        /** Typical width of `{total}` used to centre the line before the total is known. */
        const val TOTAL_SAMPLE = "10"
    }
}
