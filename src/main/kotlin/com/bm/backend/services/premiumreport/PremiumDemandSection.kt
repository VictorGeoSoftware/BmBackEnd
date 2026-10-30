package com.bm.backend.services.premiumreport

import com.bm.backend.models.DemandPeriodStatistic
import com.bm.backend.models.DemandSummary
import com.bm.backend.models.DemandWindowStatus
import com.bm.backend.models.DemandWindowSummary
import com.bm.backend.models.PeriodValues
import com.bm.backend.models.ReportDataset
import com.bm.backend.models.StudyHistoryStatus
import com.bm.backend.utils.DemandSummaryCalculator
import com.bm.backend.utils.HistoryCoverageCalculator
import com.bm.backend.utils.TariffPeriodResolver
import com.lowagie.text.Document
import com.lowagie.text.Element
import com.lowagie.text.Font
import com.lowagie.text.Paragraph
import com.lowagie.text.Phrase
import com.lowagie.text.Rectangle
import com.lowagie.text.pdf.PdfPCell
import com.lowagie.text.pdf.PdfPTable
import com.lowagie.text.pdf.PdfWriter
import java.awt.Color

/**
 * Portrait demanded-power page (spec report-demand-page V1–V9). Every figure comes from [DemandSummaryCalculator]
 * over [HistoryCoverageCalculator] (kW as published); every sentence comes from the approved truth table in
 * [PremiumDemandTexts]. Descriptive only: the contracted-power comparison is plain arithmetic with no verdict,
 * and missing data is never shown as 0. Period rows/columns follow the tariff's power periods ([TariffPeriodResolver]).
 */
class PremiumDemandSection : PremiumReportSection {

    override fun write(document: Document, writer: PdfWriter, dataset: ReportDataset) {
        val history = dataset.history
        val periods = TariffPeriodResolver.resolve(dataset).powerPeriods
        val summary = DemandSummaryCalculator.calculate(history, HistoryCoverageCalculator.calculate(history), dataset.snapshot, periods)
        val w = summary.window

        document.setPageSize(PremiumReportTheme.PAGE_PORTRAIT)
        document.newPage()
        document.add(PremiumReportBlocks.sectionBar(PremiumDemandTexts.SECTION))

        if (summary.status != StudyHistoryStatus.AVAILABLE || w == null) {
            windowLine(document, PremiumDemandTexts.NO_DATA)
            note(document, unavailableSentence(summary))
            return
        }
        windowLine(document, PremiumDemandTexts.windowValue(range(w)))

        // Block 2: statistics + notes.
        heading(document, writer, PremiumDemandTexts.STATS_TITLE)
        val figures = w.status != DemandWindowStatus.UNAVAILABLE
        if (figures) {
            document.add(statsTable(summary.periods))
            note(document, PremiumDemandTexts.STATS_CAPTION)
        }
        notes(document, summary, w, periods)

        // Block 3: comparison with contracted power.
        comparison(document, writer, summary, w, periods)

        // Blocks 4–5: readings chart and table.
        val readings = DemandChartModel.readings(summary.series)
        heading(document, writer, PremiumDemandTexts.READINGS_CHART_TITLE, readings?.let { it.height.toFloat() + 70f } ?: 60f)
        if (readings == null) {
            note(document, PremiumDemandTexts.READINGS_CHART_UNAVAILABLE)
            return
        }
        chart(document, readings, writer)
        note(document, PremiumDemandTexts.readingsCaption(periods))
        if (readings.shownRows < readings.totalRows) note(document, PremiumDemandTexts.readingsCap(readings.shownRows, readings.totalRows))
        readingsTable(document, writer, summary, periods)
    }

    companion object {
        /** N1 for a history without demand rows, N2 for any other unavailable reason. */
        fun unavailableSentence(summary: DemandSummary): String =
            if (summary.reason == DemandSummaryCalculator.NO_DEMAND_ROWS) PremiumDemandTexts.NO_DEMAND_ROWS
            else PremiumDemandTexts.DEMAND_UNAVAILABLE
    }

    // ---------- helpers ----------

    private val tableFont get() = PremiumReportTheme.regular(PremiumReportTheme.SIZE_TABLE)
    private fun cells(v: PeriodValues) = listOf(v.p1, v.p2, v.p3, v.p4, v.p5, v.p6)
    private fun range(w: DemandWindowSummary) = PremiumReportFormat.inclusiveRange(w.start, w.endExclusive)
    private fun kw(v: Double?) = v?.let { PremiumReportFormat.number(it) } ?: PremiumDemandTexts.MISSING

    private fun windowLine(document: Document, value: String) = document.add(Paragraph().apply {
        add(Phrase("${PremiumDemandTexts.WINDOW_LINE} ", PremiumReportTheme.bold()))
        add(Phrase(value, PremiumReportTheme.body()))
    })

    /** Block heading; starts a new page first when less than [need] points remain, so a heading never ends a page. */
    private fun heading(document: Document, writer: PdfWriter, text: String, need: Float = 120f) {
        if (writer.getVerticalPosition(true) - document.bottom() < need) document.newPage()
        document.add(Paragraph(text, PremiumReportTheme.bold(PremiumReportTheme.SIZE_SECTION)).apply { spacingBefore = 12f; spacingAfter = 8f })
    }

    private fun note(document: Document, text: String) = document.add(
        Paragraph(text, PremiumReportTheme.regular(PremiumReportTheme.SIZE_NOTE, PremiumReportTheme.COLOR_MUTED)).apply { spacingBefore = 4f; spacingAfter = 3f; setLeading(0f, 1.3f) }
    )

    private fun cell(text: String, font: Font = tableFont, align: Int = Element.ALIGN_RIGHT, background: Color? = null, colspan: Int = 1): PdfPCell =
        PremiumReportBlocks.cell(text, font = font, background = background, border = Rectangle.BOTTOM, align = align, padding = 3f)
            .apply { this.colspan = colspan; verticalAlignment = Element.ALIGN_MIDDLE }

    private fun header(text: String, align: Int = Element.ALIGN_RIGHT, background: Color = PremiumReportTheme.COLOR_GRID, color: Color = PremiumReportTheme.COLOR_TEXT) =
        cell(text, PremiumReportTheme.bold(PremiumReportTheme.SIZE_TABLE, color), align, background)

    private fun periodCell(n: Int) = cell("P$n", PremiumReportTheme.bold(PremiumReportTheme.SIZE_TABLE, PremiumReportTheme.PERIOD_TEXT_COLORS[n - 1]),
        Element.ALIGN_LEFT, PremiumReportTheme.PERIOD_COLORS[n - 1])

    private fun table(widths: FloatArray): PdfPTable = PdfPTable(widths).apply {
        widthPercentage = 100f
        headerRows = 1
        isSplitLate = false
        setSpacingBefore(2f)
    }

    private fun chart(document: Document, model: ActiveChartModel, writer: PdfWriter) {
        document.add(PdfPTable(1).apply {
            widthPercentage = 100f
            keepTogether = true
            addCell(PdfPCell(ActiveChartPainter.image(writer, model), false).apply {
                border = Rectangle.NO_BORDER
                setPadding(0f)
                horizontalAlignment = Element.ALIGN_LEFT
            })
        })
    }

    // ---------- C1–C3 / M1 / M2 statistics table ----------

    private fun statsTable(periods: List<DemandPeriodStatistic>): PdfPTable {
        val t = table(floatArrayOf(0.7f, 0.8f, 1.7f, 2.2f, 1.9f)).apply { keepTogether = true }
        t.addCell(header(PremiumDemandTexts.COL_PERIOD, Element.ALIGN_LEFT))
        t.addCell(header(PremiumDemandTexts.COL_READINGS))
        t.addCell(header(PremiumDemandTexts.COL_PEAK))
        t.addCell(header(PremiumDemandTexts.COL_PEAK_READING, Element.ALIGN_LEFT).apply { paddingLeft = 12f })
        t.addCell(header(PremiumDemandTexts.COL_MEAN))
        periods.sortedBy { it.period }.forEach { p ->
            t.addCell(periodCell(p.period))
            t.addCell(cell(p.readings.toString()))
            if (p.reason != null) {
                // C3: no readings in the window.
                t.addCell(cell(PremiumDemandTexts.MISSING))
                t.addCell(cell(PremiumDemandTexts.MISSING, align = Element.ALIGN_LEFT).apply { paddingLeft = 12f })
                t.addCell(cell(PremiumDemandTexts.MISSING))
                return@forEach
            }
            t.addCell(cell(kw(p.peakKw)))
            val start = p.peakStart
            val end = p.peakEndExclusive
            t.addCell(cell(if (start != null && end != null) PremiumReportFormat.inclusiveRange(start, end) else PremiumDemandTexts.MISSING, align = Element.ALIGN_LEFT)
                .apply { paddingLeft = 12f })
            val mean = p.meanKw
            t.addCell(cell(when {
                mean != null -> PremiumReportFormat.number(mean)
                p.meanReason == DemandSummaryCalculator.WINDOW_INCOMPLETE -> PremiumDemandTexts.MEAN_WINDOW_INCOMPLETE
                p.meanReason == DemandSummaryCalculator.OVERLAP -> PremiumDemandTexts.MEAN_OVERLAP
                else -> PremiumDemandTexts.MISSING
            }))
        }
        return t
    }

    // ---------- N3–N10 status notes (truth-table order) ----------

    private fun notes(document: Document, s: DemandSummary, w: DemandWindowSummary, periods: List<Int>) {
        val figures = w.status != DemandWindowStatus.UNAVAILABLE
        val withReadings = s.periods.filter { it.readings > 0 }
        val noReadings = s.periods.filter { it.reason == DemandSummaryCalculator.NO_READINGS }.map { it.period }.sorted()
        if (!figures) note(document, PremiumDemandTexts.NOTHING_IN_WINDOW)
        if (w.status == DemandWindowStatus.INCOMPLETE) note(document, PremiumDemandTexts.partialWindow(w.coveredDays, w.windowDays))
        if (w.crossing.isNotEmpty()) note(document, PremiumDemandTexts.crossing(w.crossing.size))
        if (figures && withReadings.isNotEmpty() && withReadings.all { it.peakKw == 0.0 }) note(document, PremiumDemandTexts.RECORDED_ZERO)
        if (figures && w.nullCells > 0) note(document, PremiumDemandTexts.NULL_CELLS)
        if (figures && noReadings.isNotEmpty() && noReadings.size < periods.size) note(document, PremiumDemandTexts.noReadings(noReadings))
        w.overlapDisclosures.forEach { o ->
            note(document, PremiumDemandTexts.overlapNote(PremiumReportFormat.inclusiveRange(o.start, o.endExclusive, "–")))
        }
        if (s.series.any { it.activeAllZero }) note(document, PremiumDemandTexts.ACTIVE_ZERO_NOTE)
    }

    // ---------- K0–K8 / KU1 / KU2 comparison ----------

    private fun comparison(document: Document, writer: PdfWriter, s: DemandSummary, w: DemandWindowSummary, periods: List<Int>) {
        val model = if (w.status != DemandWindowStatus.UNAVAILABLE && s.snapshotReason == null) DemandChartModel.comparison(s.periods, periods) else null
        heading(document, writer, PremiumDemandTexts.COMPARISON_TITLE, model?.let { it.height.toFloat() + 80f } ?: 60f)
        when {
            w.status == DemandWindowStatus.UNAVAILABLE -> { note(document, PremiumDemandTexts.COMPARISON_NO_READINGS); return }
            model == null -> { note(document, PremiumDemandTexts.COMPARISON_NO_CONTRACTED); return }
        }
        note(document, PremiumDemandTexts.COMPARISON_BASIS)
        chart(document, model!!, writer)
        note(document, PremiumDemandTexts.COMPARISON_CAPTION)
        if (w.status == DemandWindowStatus.INCOMPLETE) note(document, PremiumDemandTexts.COMPARISON_PARTIAL)
        val t = table(floatArrayOf(0.8f, 1.5f, 1.5f, 1.3f, 1.5f)).apply { keepTogether = true; setSpacingBefore(6f) }
        t.addCell(header(PremiumDemandTexts.COL_PERIOD, Element.ALIGN_LEFT))
        t.addCell(header(PremiumDemandTexts.COL_PEAK))
        t.addCell(header(PremiumDemandTexts.COL_CONTRACTED))
        t.addCell(header(PremiumDemandTexts.COL_DIFF))
        t.addCell(header(PremiumDemandTexts.COL_PCT))
        s.periods.sortedBy { it.period }.forEach { p ->
            t.addCell(periodCell(p.period))
            t.addCell(cell(kw(p.peakKw)))
            t.addCell(cell(kw(p.contractedKw)))
            val diff = p.peakMinusContractedKw
            val pct = p.peakPctOfContracted
            when (p.comparisonReason) {
                null -> {
                    t.addCell(cell(diff?.let { PremiumReportFormat.signedNumber(it) } ?: PremiumDemandTexts.MISSING))
                    t.addCell(cell(pct?.let { PremiumReportFormat.percent(it) } ?: PremiumDemandTexts.MISSING))
                }
                DemandSummaryCalculator.CONTRACTED_MISSING -> t.addCell(cell(PremiumDemandTexts.NO_CONTRACTED, colspan = 2))
                DemandSummaryCalculator.CONTRACTED_ZERO -> t.addCell(cell(PremiumDemandTexts.CONTRACTED_ZERO, colspan = 2))
                else -> t.addCell(cell(PremiumDemandTexts.NO_READINGS_CELL, colspan = 2))
            }
        }
        document.add(t)
    }

    // ---------- T0–T3 readings table ----------

    private fun readingsTable(document: Document, writer: PdfWriter, s: DemandSummary, periods: List<Int>) {
        heading(document, writer, PremiumDemandTexts.READINGS_TABLE_TITLE)
        val t = table((listOf(1.45f, 1.25f, 0.6f) + List(periods.size) { 1f }).toFloatArray())
        t.addCell(header(PremiumDemandTexts.COL_FROM, Element.ALIGN_LEFT))
        t.addCell(header(PremiumDemandTexts.COL_TO, Element.ALIGN_LEFT))
        t.addCell(header(PremiumDemandTexts.COL_DAYS))
        periods.forEach { n ->
            t.addCell(header("P$n${PremiumDemandTexts.UNIT_SUFFIX}", background = PremiumReportTheme.PERIOD_COLORS[n - 1], color = PremiumReportTheme.PERIOD_TEXT_COLORS[n - 1]))
        }
        s.series.forEach { p ->
            val marks = (if (p.inOverlap) PremiumDemandTexts.OVERLAP_MARK else "") + (if (p.activeAllZero) PremiumDemandTexts.ACTIVE_ZERO_MARK else "")
            t.addCell(cell(PremiumReportFormat.date(p.start) + marks, align = Element.ALIGN_LEFT))
            t.addCell(cell(PremiumReportFormat.date(p.endExclusive.minusDays(1)), align = Element.ALIGN_LEFT))
            t.addCell(cell(p.days.toString()))
            val values = cells(p.values)
            periods.forEach { n -> t.addCell(cell(kw(values[n - 1]))) }
        }
        document.add(t)
        note(document, PremiumDemandTexts.DATES_NOTE)
        if (s.series.any { it.inOverlap }) note(document, PremiumDemandTexts.OVERLAP_FOOTNOTE)
        if (s.series.any { it.activeAllZero }) note(document, PremiumDemandTexts.ACTIVE_ZERO_FOOTNOTE)
    }
}
