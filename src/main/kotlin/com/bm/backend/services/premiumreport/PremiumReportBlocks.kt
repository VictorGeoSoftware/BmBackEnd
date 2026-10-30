package com.bm.backend.services.premiumreport

import com.lowagie.text.Element
import com.lowagie.text.Font
import com.lowagie.text.Phrase
import com.lowagie.text.Rectangle
import com.lowagie.text.pdf.PdfPCell
import com.lowagie.text.pdf.PdfPTable
import java.awt.Color

/** Reusable building blocks for Premium report pages (yellow section bar, wrapping label/value rows). */
object PremiumReportBlocks {
    fun cell(
        text: String,
        font: Font = PremiumReportTheme.body(),
        background: Color? = null,
        border: Int = Rectangle.NO_BORDER,
        align: Int = Element.ALIGN_LEFT,
        padding: Float = 5f
    ): PdfPCell = PdfPCell(Phrase(text, font)).apply {
        this.border = border
        borderColor = PremiumReportTheme.COLOR_GRID
        if (background != null) backgroundColor = background
        horizontalAlignment = align
        verticalAlignment = Element.ALIGN_TOP
        setPadding(padding)
        setPaddingBottom(padding + 2f)
        isUseAscender = true
        setLeading(0f, 1.3f)
    }

    /** Full-width yellow bar with dark bold text. */
    fun sectionBar(title: String): PdfPTable = PdfPTable(1).apply {
        widthPercentage = 100f
        setSpacingAfter(10f)
        addCell(
            cell(
                title,
                font = PremiumReportTheme.bold(PremiumReportTheme.SIZE_SECTION),
                background = PremiumReportTheme.COLOR_HIGHLIGHT,
                padding = 7f
            ).apply { setPaddingLeft(10f) }
        )
    }

    /** Two-column label/value rows that wrap (no fixed heights, never clipped) separated by light gridlines. */
    fun labelValueTable(rows: List<Pair<String, String>>): PdfPTable = PdfPTable(floatArrayOf(1.15f, 2.85f)).apply {
        widthPercentage = 100f
        isSplitLate = false
        rows.forEach { (label, value) ->
            addCell(cell(label, font = PremiumReportTheme.bold(), border = Rectangle.BOTTOM))
            addCell(cell(value, border = Rectangle.BOTTOM))
        }
    }
}
