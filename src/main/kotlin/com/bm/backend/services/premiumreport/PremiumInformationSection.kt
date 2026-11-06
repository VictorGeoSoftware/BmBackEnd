package com.bm.backend.services.premiumreport

import com.bm.backend.models.ReportDataset
import com.lowagie.text.Chunk
import com.lowagie.text.Document
import com.lowagie.text.Element
import com.lowagie.text.Phrase
import com.lowagie.text.Rectangle
import com.lowagie.text.pdf.PdfPCell
import com.lowagie.text.pdf.PdfPTable
import com.lowagie.text.pdf.PdfWriter
import java.awt.Color

/**
 * Portrait closing page (feedback 2026-11-03, replaces the data-protection page): coloured title bars and the
 * approved information/maintenance copy from [PremiumInformationTexts]; the PowerApp logo comes from
 * [PremiumPageDecorator]. Independent of the dataset: no customer value is ever rendered. Fails loudly if the copy does not fit on one page.
 */
class PremiumInformationSection : PremiumReportSection {

    override fun write(document: Document, writer: PdfWriter, dataset: ReportDataset) {
        document.setPageSize(PremiumReportTheme.PAGE_PORTRAIT)
        document.newPage()
        val page = writer.pageNumber

        document.add(bar(PremiumInformationTexts.TITLE, COLOR_TITLE).apply { setSpacingBefore(6f); setSpacingAfter(10f) })
        PremiumInformationTexts.INTRO.forEach { document.add(paragraph(it, indent = 0f)) }
        document.add(bar(PremiumInformationTexts.SERVICES_TITLE, COLOR_SERVICES).apply { setSpacingBefore(BAR_GAP) })
        PremiumInformationTexts.SERVICES.forEach { document.add(paragraph(it)) }
        document.add(bar(PremiumInformationTexts.ADVICE_TITLE, COLOR_ADVICE).apply { setSpacingBefore(BAR_GAP) })
        PremiumInformationTexts.ADVICE.forEach { document.add(paragraph(it)) }

        check(writer.pageNumber == page) { "Information page copy does not fit on one page" }
    }

    private fun bar(title: String, background: Color): PdfPTable = PdfPTable(1).apply {
        widthPercentage = 100f
        setSpacingAfter(8f)
        addCell(
            PremiumReportBlocks.cell(
                title,
                font = PremiumReportTheme.bold(PremiumReportTheme.SIZE_SECTION - 1f, PremiumReportTheme.COLOR_WHITE),
                background = background,
                align = Element.ALIGN_RIGHT,
                padding = 5f
            ).apply { setPaddingRight(8f) }
        )
    }

    /**
     * Left-aligned body paragraph (as in the reference); `**…**` segments are bold. Wrapped in a borderless table so it shares the bars'
     * content layer and the reading order equals the visual order.
     */
    private fun paragraph(text: String, indent: Float = ITEM_INDENT): PdfPTable {
        val phrase = Phrase("", PremiumReportTheme.regular(SIZE))
        text.split("**").forEachIndexed { i, part ->
            if (part.isNotEmpty()) phrase.add(Chunk(part, if (i % 2 == 1) PremiumReportTheme.bold(SIZE) else PremiumReportTheme.regular(SIZE)))
        }
        return PdfPTable(1).apply {
            widthPercentage = 100f
            addCell(PdfPCell(phrase).apply {
                border = Rectangle.NO_BORDER
                horizontalAlignment = Element.ALIGN_LEFT
                setLeading(0f, 1.3f)
                setPadding(0f)
                setPaddingLeft(indent)
                setPaddingBottom(PARAGRAPH_GAP)
            })
        }
    }

    private companion object {
        /** Body size raised in the layout pass (2026-11-04) so the page reads like the original and fills the sheet. */
        const val SIZE = PremiumReportTheme.SIZE_BODY
        const val ITEM_INDENT = 24f
        const val BAR_GAP = 16f
        const val PARAGRAPH_GAP = 8f
        /** Reference page palette: red title, green services, dark-blue advice. */
        val COLOR_TITLE = Color(0xC0, 0x1B, 0x1B)
        val COLOR_SERVICES = Color(0x4E, 0x8A, 0x3E)
        val COLOR_ADVICE = Color(0x1F, 0x4E, 0x79)
    }
}
