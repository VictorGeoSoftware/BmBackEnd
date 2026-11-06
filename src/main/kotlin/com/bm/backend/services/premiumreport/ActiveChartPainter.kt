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
        model.markers.forEach { marker(t, it) }
        axes(t, model)
        labels(t, model)
        legend(t, model)
        model.bars.forEach { b -> b.innerLabel?.let { text(t, it, f(b.x + b.width / 2), f(b.y + b.height / 2) - 3f, TICK_SIZE, PdfContentByte.ALIGN_CENTER) } }
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
        val fill: Color = b.color ?: b.period?.let { PremiumReportTheme.PERIOD_COLORS[it - 1] } ?: BAR_COLOR
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

    /** Neutral dark vertical line across the marker's band (same colour for every marker), on a thin white halo. */
    private fun marker(t: PdfTemplate, k: ChartMarker) {
        t.saveState()
        t.setColorStroke(PremiumReportTheme.COLOR_WHITE)
        t.setLineWidth(3f)
        t.moveTo(f(k.x), f(k.y)); t.lineTo(f(k.x), f(k.y + k.height))
        t.stroke()
        t.setColorStroke(PremiumReportTheme.COLOR_TEXT)
        t.setLineWidth(1.2f)
        t.moveTo(f(k.x), f(k.y)); t.lineTo(f(k.x), f(k.y + k.height))
        t.stroke()
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

    /** Coloured squares with `Pn`, right-aligned above the plot. */
    private fun legend(t: PdfTemplate, m: ActiveChartModel) {
        if (m.legend.isEmpty()) return
        val item = 34f
        var x = f(m.plotLeft + m.plotWidth) - item * m.legend.size
        val y = f(m.plotBottom + m.plotHeight) + 14f
        m.legend.forEach { p ->
            t.saveState()
            t.setColorFill(PremiumReportTheme.PERIOD_COLORS[p - 1])
            t.rectangle(x, y, 8f, 8f)
            t.fill()
            t.restoreState()
            text(t, "P$p", x + 11f, y + 1f, TICK_SIZE, PdfContentByte.ALIGN_LEFT)
            x += item
        }
    }

    private fun text(t: PdfTemplate, s: String, x: Float, y: Float, size: Float, align: Int, color: Color = PremiumReportTheme.COLOR_TEXT, rotation: Float = 0f) {
        t.beginText()
        t.setFontAndSize(PremiumReportTheme.regularBaseFont(), size)
        t.setColorFill(color)
        t.showTextAligned(align, s, x, y, rotation)
        t.endText()
    }

    private fun labels(t: PdfTemplate, m: ActiveChartModel) {
        val muted = PremiumReportTheme.COLOR_MUTED
        if (m.horizontal) {
            m.valueTicks.forEach { text(t, it.label, f(m.plotLeft + it.position), f(m.plotBottom) - 11f, TICK_SIZE, PdfContentByte.ALIGN_CENTER, muted) }
            m.bars.filter { it.label.isNotEmpty() }.forEach { b -> text(t, b.label, 0f, f(b.y + b.height / 2) - 3f, LABEL_SIZE, PdfContentByte.ALIGN_LEFT) }
        } else {
            m.valueTicks.forEach { text(t, it.label, f(m.plotLeft) - 4f, f(m.plotBottom + it.position) - 3f, TICK_SIZE, PdfContentByte.ALIGN_RIGHT, muted) }
            m.categoryTicks.forEach {
                val x = f(m.plotLeft + it.position)
                t.saveState(); t.setColorStroke(muted); t.setLineWidth(0.5f)
                t.moveTo(x, f(m.plotBottom)); t.lineTo(x, f(m.plotBottom) - 3f); t.stroke(); t.restoreState()
                if (it.rotated) text(t, it.label, x + 3f, f(m.plotBottom) - 7f, TICK_SIZE, PdfContentByte.ALIGN_RIGHT, muted, 45f)
                else text(t, it.label, x, f(m.plotBottom) - 13f, TICK_SIZE, PdfContentByte.ALIGN_CENTER, muted)
            }
            text(t, m.unit, f(m.plotLeft) - 4f, f(m.plotBottom + m.plotHeight) + 6f, LABEL_SIZE, PdfContentByte.ALIGN_LEFT)
        }
    }
}
