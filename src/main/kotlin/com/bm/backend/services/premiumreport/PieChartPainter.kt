package com.bm.backend.services.premiumreport

import com.lowagie.text.Image
import com.lowagie.text.pdf.PdfContentByte
import com.lowagie.text.pdf.PdfWriter

/** Draws a [PieChartModel] with vector wedges and a labelled legend (`Pn xx,x %`) to its right. */
object PieChartPainter {
    private const val LABEL_SIZE = 9f

    fun image(writer: PdfWriter, model: PieChartModel): Image {
        val t = writer.directContent.createTemplate(model.width.toFloat(), model.height.toFloat())
        val cx = model.centerX.toFloat()
        val cy = model.centerY.toFloat()
        val r = model.radius.toFloat()
        model.slices.forEach { s ->
            t.saveState()
            t.setColorFill(PremiumReportTheme.PERIOD_COLORS[s.period - 1])
            t.setColorStroke(PremiumReportTheme.COLOR_WHITE)
            t.setLineWidth(0.8f)
            t.moveTo(cx, cy)
            val curves = PdfContentByte.bezierArc(cx - r, cy - r, cx + r, cy + r, s.startDegrees.toFloat(), s.extentDegrees.toFloat())
            val first = curves.first()
            t.lineTo(first[0], first[1])
            curves.forEach { c -> t.curveTo(c[2], c[3], c[4], c[5], c[6], c[7]) }
            t.closePath()
            t.fillStroke()
            t.restoreState()
        }
        val legendX = cx + r + 60f
        var y = cy + 12f * model.slices.size / 2f
        model.slices.forEach { s ->
            t.saveState()
            t.setColorFill(PremiumReportTheme.PERIOD_COLORS[s.period - 1])
            t.rectangle(legendX, y - 1f, 9f, 9f)
            t.fill()
            t.restoreState()
            t.beginText()
            t.setFontAndSize(PremiumReportTheme.regularBaseFont(), LABEL_SIZE)
            t.setColorFill(PremiumReportTheme.COLOR_TEXT)
            t.showTextAligned(PdfContentByte.ALIGN_LEFT, s.label, legendX + 14f, y, 0f)
            t.endText()
            y -= 14f
        }
        return Image.getInstance(t)
    }
}
