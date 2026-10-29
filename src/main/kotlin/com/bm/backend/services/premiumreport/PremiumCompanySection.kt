package com.bm.backend.services.premiumreport

import com.bm.backend.models.ReportDataset
import com.lowagie.text.Document
import com.lowagie.text.Element
import com.lowagie.text.Image
import com.lowagie.text.Paragraph
import com.lowagie.text.Phrase
import com.lowagie.text.Rectangle
import com.lowagie.text.pdf.ColumnText
import com.lowagie.text.pdf.PdfPCell
import com.lowagie.text.pdf.PdfPTable
import com.lowagie.text.pdf.PdfWriter

/**
 * Portrait closing page: BM logo, data-protection section bar, company legal name and the approved
 * data-protection text. Independent of the dataset: no customer value is ever rendered.
 */
class PremiumCompanySection(
    private val logoResource: String = PremiumCoverSection.LOGO_RESOURCE
) : PremiumReportSection {

    override fun write(document: Document, writer: PdfWriter, dataset: ReportDataset) {
        val logo = loadLogo()
        document.setPageSize(PremiumReportTheme.PAGE_PORTRAIT)
        document.newPage()

        logo.scaleToFit(LOGO_MAX_WIDTH, LOGO_MAX_HEIGHT)
        logo.alignment = Element.ALIGN_CENTER
        document.add(logo)
        document.add(Paragraph(" ", PremiumReportTheme.body()).apply { spacingAfter = 20f })
        document.add(PremiumReportBlocks.sectionBar(PremiumCompanyTexts.DATA_PROTECTION_SECTION))
        // Tables and the legal text share the direct-content layer, so reading order = visual order.
        document.add(PdfPTable(1).apply {
            widthPercentage = 100f
            addCell(PdfPCell(Phrase(PremiumCompanyTexts.COMPANY_NAME, PremiumReportTheme.bold())).apply {
                border = Rectangle.NO_BORDER
                setPadding(0f)
                setPaddingBottom(8f)
                setLeading(0f, 1.35f)
            })
        })
        writeLegalText(document, writer)
    }

    /**
     * Justified paragraph whose last line is left-aligned with normal spacing. OpenPDF re-applies the previous
     * line's justification spacing to a paragraph's last line, so the lines are written in two passes: all but
     * the last justified, then the last one left-aligned.
     */
    private fun writeLegalText(document: Document, writer: PdfWriter) {
        val left = document.left()
        val right = document.right()
        val bottom = document.bottom()
        val top = writer.getVerticalPosition(true)

        val probe = legalColumn(writer, Element.ALIGN_JUSTIFIED).apply { setSimpleColumn(left, bottom, right, top) }
        check(!ColumnText.hasMoreText(probe.go(true))) { "Data-protection text does not fit on the company page" }
        val lines = probe.linesWritten

        val column = legalColumn(writer, Element.ALIGN_JUSTIFIED)
        if (lines > 1) {
            column.setSimpleColumn(left, top - (lines - 1) * LEGAL_LEADING - LEGAL_LEADING / 2, right, top)
            column.go()
        }
        column.alignment = Element.ALIGN_LEFT
        column.setSimpleColumn(left, bottom, right, if (lines > 1) column.yLine else top)
        column.go()
    }

    private fun legalColumn(writer: PdfWriter, align: Int) = ColumnText(writer.directContent).apply {
        addText(Phrase(PremiumCompanyTexts.DATA_PROTECTION, PremiumReportTheme.regular(PremiumReportTheme.SIZE_NOTE)))
        alignment = align
        setLeading(LEGAL_LEADING, 0f)
    }

    private fun loadLogo(): Image {
        val bytes = PremiumCompanySection::class.java.getResourceAsStream(logoResource)?.use { it.readBytes() }
            ?: throw IllegalStateException("Missing report logo resource $logoResource")
        return Image.getInstance(bytes)
    }

    private companion object {
        const val LOGO_MAX_WIDTH = 180f
        const val LOGO_MAX_HEIGHT = 106f
        const val LEGAL_LEADING = PremiumReportTheme.SIZE_NOTE * 1.35f
    }
}
