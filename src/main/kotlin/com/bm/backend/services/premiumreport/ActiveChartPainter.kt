package com.bm.backend.services.premiumreport

import com.lowagie.text.Image
import com.lowagie.text.pdf.PdfContentByte
import com.lowagie.text.pdf.PdfTemplate
import com.lowagie.text.pdf.PdfWriter
import java.awt.Color

/** Thin painter: draws an [ActiveChartModel] with OpenPDF vector primitives into a template wrapped as an Image. */
object ActiveChartPainter {
    private const val TICK_SIZE = 8f
    private const val LABEL_SIZE = 9f
    private val BAR_COLOR = PremiumReportTheme.COLOR_MUTED

    fun image(writer: PdfWriter, model: ActiveChartModel): Image {
        val t = writer.directContent.createTemplate(model.width.toFloat(), model.height.toFloat())
        grid(t, model)
        model.bars.forEach { bar(t, model, it) }
        axes(t, model)
        labels(t, model)
        return Image.getInstance(t)
    }

    private fun f(v: Double) = v.toFloat()

    private fun grid(t: PdfTemplate, m: ActiveChartModel) {
        t.saveState()
        t.setColorStroke(PremiumReportTheme.COLOR_GRID)
        t.setLineWidth(0.5f)
        m.valueTicks.drop(1).forEach { tick ->
            if (m.horizontal) {
                val x = f(m.plotLeft + tick.position)
                t.moveTo(x, f(m.plotBottom)); t.lineTo(x, f(m.plotBottom + m.plotHeight))
            } else {
                val y = f(m.plotBottom + tick.position)
                t.moveTo(f(m.plotLeft), y); t.lineTo(f(m.plotLeft + m.plotWidth), y)
            }
        }
        t.stroke()
        t.restoreState()
    }

    private fun bar(t: PdfTemplate, m: ActiveChartModel, b: ChartBar) {
        t.saveState()
        val fill: Color = b.period?.let { PremiumReportTheme.PERIOD_COLORS[it - 1] } ?: BAR_COLOR
        // Small inset so adjacent readings stay visually separate while keeping their true extent.
        val inset = if (m.horizontal) 0f else minOf(0.4f, f(b.width) / 4)
        val x = f(b.x) + inset
        val w = f(b.width) - 2 * inset
        when (b.style) {
            ChartBarStyle.FILLED -> {
                t.setColorFill(fill)
                t.setColorStroke(PremiumReportTheme.COLOR_TEXT)
                t.setLineWidth(0.3f)
                t.rectangle(x, f(b.y), w, f(b.height))
                if (m.horizontal) t.fillStroke() else t.fill()
            }
            ChartBarStyle.OUTLINED -> {
                t.setColorStroke(PremiumReportTheme.COLOR_TEXT)
                t.setLineWidth(0.9f)
                t.rectangle(x + 0.45f, f(b.y), w - 0.9f, f(b.height))
                t.stroke()
            }
            ChartBarStyle.ZERO_MARKER -> {
                t.setColorStroke(PremiumReportTheme.COLOR_TEXT)
                t.setLineWidth(2f)
                if (m.horizontal) { t.moveTo(x, f(b.y)); t.lineTo(x, f(b.y + b.height)) } else { t.moveTo(x, f(b.y) + 1f); t.lineTo(x + w, f(b.y) + 1f) }
                t.stroke()
            }
            ChartBarStyle.NO_DATA -> hatch(t, x, f(b.y), w, f(b.height))
        }
        t.restoreState()
    }

    private fun hatch(t: PdfTemplate, x: Float, y: Float, w: Float, h: Float) {
        t.rectangle(x, y, w, h)
        t.clip()
        t.newPath()
        t.setColorStroke(PremiumReportTheme.COLOR_GRID)
        t.setLineWidth(0.6f)
        var o = -h
        while (o < w) {
            t.moveTo(x + o, y); t.lineTo(x + o + h, y + h)
            o += 4f
        }
        t.stroke()
    }

    private fun axes(t: PdfTemplate, m: ActiveChartModel) {
        t.saveState()
        t.setColorStroke(PremiumReportTheme.COLOR_TEXT)
        t.setLineWidth(0.6f)
        t.moveTo(f(m.plotLeft), f(m.plotBottom + m.plotHeight)); t.lineTo(f(m.plotLeft), f(m.plotBottom))
        t.lineTo(f(m.plotLeft + m.plotWidth), f(m.plotBottom))
        t.stroke()
        t.restoreState()
    }

    private fun text(t: PdfTemplate, s: String, x: Float, y: Float, size: Float, align: Int, color: Color = PremiumReportTheme.COLOR_TEXT) {
        t.beginText()
        t.setFontAndSize(PremiumReportTheme.regularBaseFont(), size)
        t.setColorFill(color)
        t.showTextAligned(align, s, x, y, 0f)
        t.endText()
    }

    private fun labels(t: PdfTemplate, m: ActiveChartModel) {
        val muted = PremiumReportTheme.COLOR_MUTED
        if (m.horizontal) {
            m.valueTicks.forEach { text(t, it.label, f(m.plotLeft + it.position), f(m.plotBottom) - 11f, TICK_SIZE, PdfContentByte.ALIGN_CENTER, muted) }
            m.bars.forEach { b -> text(t, b.label, 0f, f(b.y + b.height / 2) - 3f, LABEL_SIZE, PdfContentByte.ALIGN_LEFT) }
        } else {
            m.valueTicks.forEach { text(t, it.label, f(m.plotLeft) - 4f, f(m.plotBottom + it.position) - 3f, TICK_SIZE, PdfContentByte.ALIGN_RIGHT, muted) }
            m.categoryTicks.forEach {
                val x = f(m.plotLeft + it.position)
                t.saveState(); t.setColorStroke(muted); t.setLineWidth(0.5f)
                t.moveTo(x, f(m.plotBottom)); t.lineTo(x, f(m.plotBottom) - 3f); t.stroke(); t.restoreState()
                text(t, it.label, x, f(m.plotBottom) - 13f, TICK_SIZE, PdfContentByte.ALIGN_CENTER, muted)
            }
            text(t, m.unit, f(m.plotLeft) - 4f, f(m.plotBottom + m.plotHeight) + 6f, LABEL_SIZE, PdfContentByte.ALIGN_LEFT)
        }
    }
}
