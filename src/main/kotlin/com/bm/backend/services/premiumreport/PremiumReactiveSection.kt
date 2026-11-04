package com.bm.backend.services.premiumreport

import com.bm.backend.models.PeriodValues
import com.bm.backend.models.ReactivePenaltySummary
import com.bm.backend.models.ReactivePenaltyWindowKind
import com.bm.backend.models.ReactiveRatio
import com.bm.backend.models.ReactiveSummary
import com.bm.backend.models.ReactiveWindowStatus
import com.bm.backend.models.ReactiveWindowSummary
import com.bm.backend.models.ReportDataset
import com.bm.backend.models.StudyHistory
import com.bm.backend.models.StudyHistoryStatus
import com.bm.backend.utils.HistoryCoverageCalculator
import com.bm.backend.utils.ReactivePenaltyCalculator
import com.bm.backend.utils.ReactivePenaltyPrices
import com.bm.backend.utils.ReactiveSummaryCalculator
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
 * Portrait reactive-energy page (spec report-reactive-page V1–V8). Every figure comes from [ReactiveSummaryCalculator]
 * over [HistoryCoverageCalculator]; every sentence comes from the approved truth table in [PremiumReactiveTexts].
 * Period columns, rows and bands follow the tariff's energy periods ([TariffPeriodResolver]).
 * Descriptive only: nothing is projected, prorated, judged or advised; missing data is never shown as 0.
 */
class PremiumReactiveSection : PremiumReportSection {

    override fun write(document: Document, writer: PdfWriter, dataset: ReportDataset) {
        val history = dataset.history
        val profile = TariffPeriodResolver.resolve(dataset)
        val periods = profile.energyPeriods
        val coverage = HistoryCoverageCalculator.calculate(history)
        val summary = ReactiveSummaryCalculator.calculate(history, coverage, periods)
        val w = summary.window

        document.setPageSize(PremiumReportTheme.PAGE_PORTRAIT)
        document.newPage()
        document.add(PremiumReportBlocks.sectionBar(PremiumReactiveTexts.SECTION))

        if (summary.status != StudyHistoryStatus.AVAILABLE || w == null) {
            windowLine(document, PremiumReactiveTexts.NO_DATA)
            note(document, unavailableSentence(summary))
            return
        }
        windowLine(document, PremiumReactiveTexts.windowValue(range(w)))

        heading(document, writer, PremiumReactiveTexts.WINDOW_TITLE)
        document.add(windowTable(w, periods))
        notes(document, w, history)

        val distribution = ReactiveChartModel.distribution(w, periods)
        heading(document, writer, PremiumReactiveTexts.DISTRIBUTION_TITLE, distribution?.let { it.height.toFloat() + 40f } ?: 60f)
        if (distribution != null) chart(document, writer, distribution) else note(document, distributionReason(w))

        quotient(document, writer, summary, w)

        val slots = summary.historySeries.map { ReadingSlot(it.start, it.endExclusive, it.values, it.inUnresolvedOverlap) }
        if (slots.isNotEmpty()) {
            val model = StackedReadingsChart.model(slots, periods, PremiumReactiveTexts.UNIT)
            heading(document, writer, PremiumReactiveTexts.READINGS_CHART_TITLE, model.height.toFloat() + 40f)
            chart(document, writer, model)
        }
        val penalty = ReactivePenaltyCalculator.calculate(history, summary.historySeries, coverage.anchorDate, profile)
        readingsTable(document, writer, summary, periods, penalty)
        penaltyBlock(document, writer, penalty)
    }

    // ---------- penalty estimate (spec report-reactive-penalty) ----------

    private fun penaltyBlock(document: Document, writer: PdfWriter, penalty: ReactivePenaltySummary) {
        if (!penalty.applicable) {
            note(document, PremiumReactiveTexts.PENALTY_NOT_APPLICABLE)
            return
        }
        heading(document, writer, PremiumReactiveTexts.PENALTY_TITLE)
        val t = table(floatArrayOf(1.6f, 1.2f, 1.4f, 0.8f)).apply { keepTogether = true }
        t.addCell(header(PremiumReactiveTexts.COL_WINDOW, Element.ALIGN_LEFT))
        t.addCell(header("${PremiumReactiveTexts.COL_PENALTY} (€)"))
        t.addCell(header(PremiumReactiveTexts.COL_REACTIVE_P1_P5))
        t.addCell(header(PremiumReactiveTexts.COL_COS_PHI))
        penalty.totals.forEach { total ->
            val label = when (total.kind) {
                ReactivePenaltyWindowKind.LAST_12M -> PremiumReactiveTexts.LAST_12M
                ReactivePenaltyWindowKind.PREVIOUS_12M -> PremiumReactiveTexts.PREVIOUS_12M
                ReactivePenaltyWindowKind.LAST_24M -> PremiumReactiveTexts.LAST_24M
            }
            t.addCell(cell("$label\n${PremiumReportFormat.inclusiveRange(total.start, total.endExclusive)}", align = Element.ALIGN_LEFT))
            val eur = total.penaltyEur
            if (eur == null) {
                t.addCell(cell(penaltyReason(total.reason), colspan = 3))
            } else {
                t.addCell(cell(PremiumReportFormat.euros(eur), tableBold))
                t.addCell(cell(kvarh(total.reactiveKvarh)))
                t.addCell(cell(total.cosPhi?.let { PremiumReportFormat.decimal2(it) } ?: PremiumReactiveTexts.MISSING))
            }
        }
        document.add(t)

        val slots = penalty.readings.map { r -> ReadingSlot(r.start, r.endExclusive, PeriodValues(r.penaltyEur, null, null, null, null, null), r.inUnresolvedOverlap) }
        if (slots.isNotEmpty()) {
            val model = StackedReadingsChart.model(slots, listOf(1), PremiumReactiveTexts.PENALTY_UNIT).copy(legend = emptyList())
            heading(document, writer, PremiumReactiveTexts.PENALTY_CHART_TITLE, model.height.toFloat() + 40f)
            chart(document, writer, model)
        }
        note(document, PremiumReactiveTexts.penaltyNote(ReactivePenaltyPrices.BOE_REFERENCE))
    }

    private fun penaltyReason(reason: String?) = when (reason) {
        ReactivePenaltyCalculator.OVERLAP -> PremiumReactiveTexts.PENALTY_NO_FIGURE_OVERLAP
        ReactivePenaltyCalculator.NOT_CALCULABLE -> PremiumReactiveTexts.PENALTY_NO_FIGURE_MISSING
        else -> PremiumReactiveTexts.PENALTY_NO_READINGS
    }

    companion object {
        /** N1 for a history without reactive rows, N2 for any other unavailable reason. */
        fun unavailableSentence(summary: ReactiveSummary): String =
            if (summary.reason == ReactiveSummaryCalculator.NO_REACTIVE_ROWS) PremiumReactiveTexts.NO_REACTIVE_ROWS
            else PremiumReactiveTexts.REACTIVE_UNAVAILABLE
    }

    // ---------- helpers ----------

    private val tableFont get() = PremiumReportTheme.regular(PremiumReportTheme.SIZE_TABLE)
    private val tableBold get() = PremiumReportTheme.bold(PremiumReportTheme.SIZE_TABLE)
    private fun cells(v: PeriodValues) = listOf(v.p1, v.p2, v.p3, v.p4, v.p5, v.p6)
    private fun range(w: ReactiveWindowSummary) = PremiumReportFormat.inclusiveRange(w.start, w.endExclusive)
    private fun kvarh(v: Double?) = v?.let { PremiumReportFormat.kwh(it) } ?: PremiumReactiveTexts.MISSING

    private fun windowLine(document: Document, value: String) = document.add(Paragraph().apply {
        add(Phrase("${PremiumReactiveTexts.WINDOW_LINE} ", PremiumReportTheme.bold()))
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

    /** Headers keep each period's colour (identity by period number). */
    private fun periodHeaders(table: PdfPTable, periods: List<Int>, suffix: String) = periods.forEach { p ->
        table.addCell(header("P$p$suffix", background = PremiumReportTheme.PERIOD_COLORS[p - 1], color = PremiumReportTheme.PERIOD_TEXT_COLORS[p - 1]))
    }

    private fun widths(vararg lead: Float, periods: List<Int>, trail: Float) =
        (lead.toList() + List(periods.size) { 1f } + trail).toFloatArray()

    private fun table(widths: FloatArray): PdfPTable = PdfPTable(widths).apply {
        widthPercentage = 100f
        headerRows = 1
        isSplitLate = false
        setSpacingBefore(2f)
    }

    private fun chart(document: Document, writer: PdfWriter, model: ActiveChartModel) {
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

    // ---------- H1 / C1–C7 window table ----------

    private fun windowTable(w: ReactiveWindowSummary, periods: List<Int>): PdfPTable {
        val t = table(widths(2.3f, 1.3f, 1.1f, periods = periods, trail = 1.35f))
        t.addCell(header(PremiumReactiveTexts.COL_PERIOD, Element.ALIGN_LEFT))
        t.addCell(header(PremiumReactiveTexts.COL_STATUS, Element.ALIGN_LEFT))
        t.addCell(header(PremiumReactiveTexts.COL_COVERED_DAYS))
        periodHeaders(t, periods, PremiumReactiveTexts.UNIT_SUFFIX)
        t.addCell(header(PremiumReactiveTexts.COL_TOTAL_KVARH))
        t.addCell(cell("${PremiumReactiveTexts.LATEST_WINDOW}\n${range(w)}", align = Element.ALIGN_LEFT))
        t.addCell(cell(statusText(w.status), align = Element.ALIGN_LEFT))
        when (w.status) {
            ReactiveWindowStatus.COMPLETE, ReactiveWindowStatus.INCOMPLETE -> {
                t.addCell(cell("${w.coveredDays} / ${w.windowDays}"))
                val shares = w.shares?.let { cells(it) }
                val totals = cells(w.periodTotals)
                periods.forEach { p ->
                    val v = totals[p - 1]
                    val share = shares?.get(p - 1)?.let { "\n${PremiumReportFormat.percent(it)}" } ?: ""
                    t.addCell(cell(kvarh(v) + share))
                }
                val total = if (w.status == ReactiveWindowStatus.COMPLETE) kvarh(w.total)
                else "${PremiumReactiveTexts.PARTIAL_PREFIX} ${kvarh(w.partialTotal)}"
                t.addCell(cell(total, font = tableBold))
            }
            ReactiveWindowStatus.OVERLAP_UNRESOLVED ->
                t.addCell(cell(PremiumReactiveTexts.OVERLAP_NO_FIGURE, align = Element.ALIGN_LEFT, colspan = periods.size + 2))
            ReactiveWindowStatus.UNAVAILABLE ->
                t.addCell(cell(PremiumReactiveTexts.NO_DATA, align = Element.ALIGN_LEFT, colspan = periods.size + 2))
        }
        return t
    }

    private fun statusText(s: ReactiveWindowStatus) = when (s) {
        ReactiveWindowStatus.COMPLETE -> PremiumReactiveTexts.STATUS_COMPLETE
        ReactiveWindowStatus.INCOMPLETE -> PremiumReactiveTexts.STATUS_INCOMPLETE
        ReactiveWindowStatus.OVERLAP_UNRESOLVED -> PremiumReactiveTexts.STATUS_OVERLAP
        ReactiveWindowStatus.UNAVAILABLE -> PremiumReactiveTexts.STATUS_UNAVAILABLE
    }

    // ---------- N3–N9 status notes ----------

    private fun notes(document: Document, w: ReactiveWindowSummary, history: StudyHistory) {
        val figures = w.status == ReactiveWindowStatus.COMPLETE || w.status == ReactiveWindowStatus.INCOMPLETE
        if (w.status == ReactiveWindowStatus.UNAVAILABLE) note(document, PremiumReactiveTexts.NOTHING_IN_WINDOW)
        if (w.status == ReactiveWindowStatus.INCOMPLETE) note(document, PremiumReactiveTexts.partialWindow(w.coveredDays, w.windowDays))
        if (w.crossing.isNotEmpty()) note(document, PremiumReactiveTexts.crossing(w.crossing.size))
        if (w.recordedZero) note(document, PremiumReactiveTexts.RECORDED_ZERO)
        if (w.nullCells > 0 && figures) note(document, PremiumReactiveTexts.NULL_CELLS)
        w.overlapDisclosures.forEach { o ->
            val zero = listOf(o.firstIndex, o.secondIndex).any { i ->
                history.intervals.getOrNull(i)?.reactive?.let { cells(it).all { c -> c == 0.0 } } == true
            }
            note(document, PremiumReactiveTexts.overlapNote(PremiumReportFormat.inclusiveRange(o.start, o.endExclusive, "–"), zero))
        }
    }

    // ---------- D2–D4 ----------

    private fun distributionReason(w: ReactiveWindowSummary) = when (w.status) {
        ReactiveWindowStatus.OVERLAP_UNRESOLVED -> PremiumReactiveTexts.DISTRIBUTION_OVERLAP
        ReactiveWindowStatus.COMPLETE -> PremiumReactiveTexts.DISTRIBUTION_ZERO
        else -> PremiumReactiveTexts.DISTRIBUTION_NOT_COMPLETE
    }

    // ---------- Q0 / B1 / B2 / R / U quotient ----------

    private fun quotient(document: Document, writer: PdfWriter, s: ReactiveSummary, w: ReactiveWindowSummary) {
        heading(document, writer, PremiumReactiveTexts.RATIO_TITLE)
        val start = s.activeWindowStart
        val end = s.activeWindowEndExclusive
        val basisRange = if (start != null && end != null) PremiumReportFormat.inclusiveRange(start, end) else range(w)
        note(document, PremiumReactiveTexts.basis(basisRange))
        val reason = s.ratioReason
        if (reason != null) {
            note(document, "${PremiumReactiveTexts.RATIO_UNAVAILABLE} ${unavailableReason(reason)}")
            return
        }
        val t = table(floatArrayOf(1f, 1.3f, 1.3f, 1.6f)).apply { keepTogether = true; setSpacingBefore(6f) }
        t.addCell(header(PremiumReactiveTexts.COL_PERIOD, Element.ALIGN_LEFT))
        t.addCell(header(PremiumReactiveTexts.COL_REACTIVE))
        t.addCell(header(PremiumReactiveTexts.COL_ACTIVE))
        t.addCell(header(PremiumReactiveTexts.COL_RATIO))
        (s.ratios + s.overallRatio).forEach { r -> ratioRow(t, r) }
        document.add(t)
    }

    private fun ratioRow(t: PdfPTable, r: ReactiveRatio) {
        val font = if (r.period == null) tableBold else tableFont
        t.addCell(cell(r.period?.let { "P$it" } ?: PremiumReactiveTexts.TOTAL, font, Element.ALIGN_LEFT))
        t.addCell(cell(kvarh(r.reactiveKvarh), font))
        t.addCell(cell(kvarh(r.activeKwh), font))
        val ratio = r.ratio
        t.addCell(cell(if (ratio != null) PremiumReportFormat.decimal2(ratio) else cellReason(r.reason), font))
    }

    private fun cellReason(reason: String?) = when (reason) {
        ReactiveSummaryCalculator.PERIOD_NULL_CELLS -> PremiumReactiveTexts.RATIO_NULL_CELLS
        ReactiveSummaryCalculator.ACTIVE_ZERO -> PremiumReactiveTexts.RATIO_ACTIVE_ZERO
        else -> PremiumReactiveTexts.RATIO_PERIOD_MISSING
    }

    private fun unavailableReason(reason: String) = when (reason) {
        ReactiveSummaryCalculator.REACTIVE_OVERLAP -> PremiumReactiveTexts.RATIO_REACTIVE_OVERLAP
        ReactiveSummaryCalculator.ACTIVE_UNAVAILABLE -> PremiumReactiveTexts.RATIO_ACTIVE_UNAVAILABLE
        ReactiveSummaryCalculator.ACTIVE_OVERLAP -> PremiumReactiveTexts.RATIO_ACTIVE_OVERLAP
        ReactiveSummaryCalculator.ACTIVE_INCOMPLETE -> PremiumReactiveTexts.RATIO_ACTIVE_INCOMPLETE
        else -> PremiumReactiveTexts.RATIO_REACTIVE_INCOMPLETE
    }

    // ---------- T0–T2 readings table ----------

    private fun readingsTable(document: Document, writer: PdfWriter, s: ReactiveSummary, periods: List<Int>, penalty: ReactivePenaltySummary) {
        heading(document, writer, PremiumReactiveTexts.READINGS_TABLE_TITLE)
        val byIndex = penalty.readings.associateBy { it.index }
        val lead = if (penalty.applicable) floatArrayOf(1.25f, 1.15f, 0.6f, 1.45f) else floatArrayOf(1.35f, 1.25f, 0.6f)
        val t = table(widths(*lead, periods = periods, trail = 1.0f))
        t.addCell(header(PremiumReactiveTexts.COL_FROM, Element.ALIGN_LEFT))
        t.addCell(header(PremiumReactiveTexts.COL_TO, Element.ALIGN_LEFT))
        t.addCell(header(PremiumReactiveTexts.COL_DAYS))
        if (penalty.applicable) t.addCell(header(PremiumReactiveTexts.COL_PENALTY, background = PremiumReportTheme.PERIOD_COLORS[0], color = PremiumReportTheme.COLOR_WHITE))
        periodHeaders(t, periods, PremiumReactiveTexts.UNIT_SUFFIX)
        t.addCell(header(PremiumReactiveTexts.COL_TOTAL_KVARH))
        s.historySeries.forEach { p ->
            val mark = if (p.inUnresolvedOverlap) PremiumReactiveTexts.OVERLAP_MARK else ""
            t.addCell(cell(PremiumReportFormat.date(p.start) + mark, align = Element.ALIGN_LEFT))
            t.addCell(cell(PremiumReportFormat.date(p.endExclusive.minusDays(1)), align = Element.ALIGN_LEFT))
            t.addCell(cell(p.days.toString()))
            if (penalty.applicable) {
                val eur = byIndex[p.index]?.penaltyEur
                t.addCell(cell(eur?.let { PremiumReportFormat.euros(it) } ?: "${PremiumReactiveTexts.MISSING}${PremiumReactiveTexts.PENALTY_MISSING_MARK}", tableBold))
            }
            val values = cells(p.values)
            periods.forEach { n -> t.addCell(cell(kvarh(values[n - 1]))) }
            t.addCell(cell(kvarh(p.total), tableBold))
        }
        document.add(t)
        if (penalty.readings.any { it.penaltyEur == null }) note(document, PremiumReactiveTexts.PENALTY_MISSING_FOOTNOTE)
        if (s.historySeries.any { it.inUnresolvedOverlap }) note(document, PremiumReactiveTexts.OVERLAP_FOOTNOTE)
        note(document, PremiumReactiveTexts.DATES_NOTE)
    }
}
