package com.bm.backend.services.premiumreport

import com.bm.backend.models.ReportDataset
import com.lowagie.text.Chunk
import com.lowagie.text.Document
import com.lowagie.text.Element
import com.lowagie.text.Font
import com.lowagie.text.Phrase
import com.lowagie.text.Rectangle
import com.lowagie.text.pdf.PdfPCell
import com.lowagie.text.pdf.PdfPTable
import com.lowagie.text.pdf.PdfWriter
import java.awt.Color

/**
 * Landscape economic-offer pages (spec report-comparison-page V1–V10). Source is `dataset.comparison` only; every
 * string comes from [ComparisonTableModel] (Basic's rules, Basic's labels). Per page: section bar with the
 * client's title, then the table (7 offers per page, current-conditions column repeated). No supply block (Q2 a).
 */
class PremiumComparisonSection : PremiumReportSection {

    override fun write(document: Document, writer: PdfWriter, dataset: ReportDataset) {
        val comparison = dataset.comparison
        ComparisonTableModel.pages(comparison).forEach { page ->
            document.setPageSize(PremiumReportTheme.PAGE_LANDSCAPE)
            document.newPage()
            document.add(PremiumReportBlocks.sectionBar(comparison.title))
            document.add(table(page))
        }
    }

    private fun table(page: ComparisonPage): PdfPTable = PdfPTable(page.columnCount).apply {
        keepTogether = true
        isSplitLate = false
        horizontalAlignment = Element.ALIGN_LEFT
        val widths = FloatArray(page.columnCount) { WIDTH_OFFER }
        widths[0] = WIDTH_SIDE
        widths[1] = WIDTH_CONSUMPTION
        widths[2] = WIDTH_CUSTOMER
        setTotalWidth(widths)
        setLockedWidth(true)
        page.cells.forEach { addCell(cell(it)) }
    }

    private fun cell(c: ComparisonCell): PdfPCell {
        val pdfCell = when (c.style) {
            ComparisonCellStyle.SIDE_TITLE_ENERGY -> {
                val title = c.text.substringBefore("\n\n")
                val total = c.text.substringAfter("\n\n")
                PdfPCell(Phrase().apply {
                    add(Chunk(title, label))
                    add(Chunk("\n\n", label))
                    add(Chunk(total, highlight))
                })
            }
            else -> PdfPCell(Phrase(c.text, font(c.style)))
        }
        return pdfCell.apply {
            colspan = c.colSpan
            rowspan = c.rowSpan
            horizontalAlignment = Element.ALIGN_CENTER
            verticalAlignment = Element.ALIGN_MIDDLE
            border = if (c.style == ComparisonCellStyle.BLANK) Rectangle.NO_BORDER else Rectangle.BOX
            borderColor = Color.BLACK
            backgroundColor = background(c.style)
            setPadding(if (c.style.isHeader()) 3f else 2f)
            setPaddingBottom((if (c.style.isHeader()) 3f else 2f) + 1f)
            isUseAscender = true
            if (c.style == ComparisonCellStyle.SAVINGS || c.style == ComparisonCellStyle.SAVINGS_NEGATIVE) {
                minimumHeight = SAVINGS_MIN_HEIGHT
            }
        }
    }

    private fun ComparisonCellStyle.isHeader() = this == ComparisonCellStyle.HEADER_HIGHLIGHT ||
        this == ComparisonCellStyle.HEADER_CURRENT || this == ComparisonCellStyle.HEADER_OFFER

    private val value: Font get() = PremiumReportTheme.regular(SIZE_TEXT, Color.BLACK)
    private val label: Font get() = PremiumReportTheme.bold(SIZE_TEXT, Color.BLACK)
    private val highlight: Font get() = PremiumReportTheme.bold(SIZE_EMPHASIS, Color.BLACK)

    private fun font(style: ComparisonCellStyle): Font = when (style) {
        ComparisonCellStyle.VALUE, ComparisonCellStyle.BLANK -> value
        ComparisonCellStyle.SAVINGS, ComparisonCellStyle.SAVINGS_NEGATIVE -> highlight
        else -> label
    }

    private fun background(style: ComparisonCellStyle): Color = when (style) {
        ComparisonCellStyle.HEADER_HIGHLIGHT, ComparisonCellStyle.TOTAL -> PremiumReportTheme.COLOR_HIGHLIGHT
        ComparisonCellStyle.HEADER_CURRENT -> COLOR_CURRENT_HEADER
        ComparisonCellStyle.SAVINGS -> COLOR_SAVINGS
        ComparisonCellStyle.SAVINGS_NEGATIVE -> COLOR_SAVINGS_NEGATIVE
        else -> Color.WHITE
    }

    companion object {
        /** Basic's locked widths (122.4/82.8/90/72) × Premium/Basic content width (757.89/813.89 ≈ 0.931). */
        const val WIDTH_SIDE = 114.0f
        const val WIDTH_CONSUMPTION = 77.1f
        const val WIDTH_CUSTOMER = 83.8f
        const val WIDTH_OFFER = 67.0f

        const val SIZE_TEXT = 8f
        const val SIZE_EMPHASIS = 10f
        const val SAVINGS_MIN_HEIGHT = 36f

        /** Basic comparator colours (Q4 a). */
        val COLOR_CURRENT_HEADER = Color(178, 208, 230)
        val COLOR_SAVINGS = Color(171, 231, 179)
        val COLOR_SAVINGS_NEGATIVE = Color(245, 178, 178)
    }
}
