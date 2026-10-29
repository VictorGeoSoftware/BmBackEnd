package com.bm.backend.services.premiumreport

import com.lowagie.text.Font
import com.lowagie.text.PageSize
import com.lowagie.text.Rectangle
import com.lowagie.text.pdf.BaseFont
import java.awt.Color

/**
 * Shared visual theme for the Premium comprehensive report: page sizes, margins, colours and the embedded
 * Liberation Sans fonts (SIL OFL 1.1, see `fonts/LICENSE-LiberationSans.txt`). Colours follow the Basic PDF.
 */
object PremiumReportTheme {
    val PAGE_PORTRAIT: Rectangle = PageSize.A4
    val PAGE_LANDSCAPE: Rectangle = PageSize.A4.rotate()

    const val MARGIN_HORIZONTAL = 42f
    const val MARGIN_VERTICAL = 36f

    val COLOR_HIGHLIGHT = Color(249, 196, 17)
    val COLOR_TEXT = Color(0x1F, 0x29, 0x37)
    val COLOR_MUTED = Color(0x47, 0x55, 0x69)
    val COLOR_GRID = Color(0xCB, 0xD5, 0xE1)
    val COLOR_WHITE: Color = Color.WHITE

    const val SIZE_BODY = 10.5f
    const val SIZE_SECTION = 12f
    const val SIZE_SUBTITLE = 14f
    const val SIZE_TITLE = 20f

    private const val REGULAR_RESOURCE = "/fonts/LiberationSans-Regular.ttf"
    private const val BOLD_RESOURCE = "/fonts/LiberationSans-Bold.ttf"

    private val regular: BaseFont by lazy { load(REGULAR_RESOURCE) }
    private val bold: BaseFont by lazy { load(BOLD_RESOURCE) }

    fun regular(size: Float = SIZE_BODY, color: Color = COLOR_TEXT): Font = Font(regular, size, Font.NORMAL, color)

    fun bold(size: Float = SIZE_BODY, color: Color = COLOR_TEXT): Font = Font(bold, size, Font.NORMAL, color)

    fun body(): Font = regular()

    /** Identity-H + embedded: subsets of the bundled TrueType files are embedded in every PDF. */
    private fun load(resource: String): BaseFont {
        val bytes = PremiumReportTheme::class.java.getResourceAsStream(resource)?.use { it.readBytes() }
            ?: throw IllegalStateException("Missing report font resource $resource")
        return BaseFont.createFont(resource.substringAfterLast('/'), BaseFont.IDENTITY_H, BaseFont.EMBEDDED, true, bytes, null)
    }
}
