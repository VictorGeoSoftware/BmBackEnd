package com.bm.backend.services.premiumreport

import com.bm.backend.models.ActiveSummary
import com.bm.backend.models.ActiveWindowKind
import com.bm.backend.models.ActiveWindowStatus
import com.bm.backend.models.ActiveWindowSummary
import com.bm.backend.models.AnnualSource
import com.bm.backend.models.AnnualSourceComparison
import com.bm.backend.models.ComparisonStatus
import com.bm.backend.models.PeriodValues
import com.bm.backend.models.ReportDataset
import com.bm.backend.models.StudyHistory
import com.bm.backend.models.StudyHistoryStatus
import com.bm.backend.models.ValueComparison
import com.bm.backend.utils.ActiveSummaryCalculator
import com.bm.backend.utils.HistoryCoverageCalculator
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
import java.time.temporal.ChronoUnit

/**
 * Portrait active-energy page (spec report-active-page D2–D9). Every figure comes from [ActiveSummaryCalculator] /
 * [HistoryCoverageCalculator] or the source rows as published; nothing is projected, prorated or advised.
 */
class PremiumActiveSection : PremiumReportSection {

    override fun write(document: Document, writer: PdfWriter, dataset: ReportDataset) {
        val history = dataset.history
        val coverage = HistoryCoverageCalculator.calculate(history)
        val summary = ActiveSummaryCalculator.calculate(history, coverage, dataset.snapshot, dataset.report.consumptionData)
        val latest = summary.windows.firstOrNull { it.kind == ActiveWindowKind.LATEST_12M }

        document.setPageSize(PremiumReportTheme.PAGE_PORTRAIT)
        document.newPage()
        document.add(PremiumReportBlocks.sectionBar(PremiumActiveTexts.SECTION))
        coverageLine(document, summary)

        heading(document, writer, PremiumActiveTexts.WINDOWS_TITLE)
        document.add(windowTable(summary))
        notes(document, summary, history)

        val distribution = latest?.let { ActiveChartModel.distribution(it) }
        heading(document, writer, PremiumActiveTexts.DISTRIBUTION_TITLE, distribution?.let { it.height.toFloat() + 40f } ?: 60f)
        if (distribution != null) chart(document, writer, distribution)
        else note(document, "${PremiumActiveTexts.DISTRIBUTION_UNAVAILABLE} ${distributionReason(latest)}")

        val readings = ActiveChartModel.readings(history, coverage)
        heading(document, writer, PremiumActiveTexts.READINGS_CHART_TITLE, readings?.let { it.height.toFloat() + 70f } ?: 60f)
        if (readings != null) {
            chart(document, writer, readings)
            note(document, PremiumActiveTexts.READINGS_CAPTION)
            if (readings.shownRows < readings.totalRows) note(document, PremiumActiveTexts.readingsCap(readings.shownRows, readings.totalRows))
        } else note(document, PremiumActiveTexts.READINGS_CHART_UNAVAILABLE)

        highIntervals(document, writer, summary)
        comparisons(document, writer, summary, latest)
        readingsTable(document, writer, history, latest)
    }

    // ---------- text helpers ----------

    private val tableFont get() = PremiumReportTheme.regular(PremiumReportTheme.SIZE_TABLE)
    private val tableBold get() = PremiumReportTheme.bold(PremiumReportTheme.SIZE_TABLE)
    private fun cells(v: PeriodValues) = listOf(v.p1, v.p2, v.p3, v.p4, v.p5, v.p6)

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

    private fun periodHeaders(table: PdfPTable, suffix: String = "") = (0 until 6).forEach { i ->
        table.addCell(header("P${i + 1}$suffix", background = PremiumReportTheme.PERIOD_COLORS[i], color = PremiumReportTheme.PERIOD_TEXT_COLORS[i]))
    }

    private fun table(widths: FloatArray): PdfPTable = PdfPTable(widths).apply {
        widthPercentage = 100f
        headerRows = 1
        isSplitLate = false
        setSpacingBefore(2f)
    }

    private fun kwh(v: Double?) = v?.let { PremiumReportFormat.kwh(it) } ?: PremiumActiveTexts.MISSING

    private fun windowName(w: ActiveWindowSummary) =
        if (w.kind == ActiveWindowKind.LATEST_12M) PremiumActiveTexts.LATEST_WINDOW else PremiumActiveTexts.PREVIOUS_WINDOW

    private fun windowLabel(w: ActiveWindowSummary) =
        "${windowName(w).replaceFirstChar { it.lowercase(PremiumReportFormat.LOCALE) }} (${PremiumReportFormat.inclusiveRange(w.start, w.endExclusive)})"

    private fun statusText(w: ActiveWindowSummary) = when (w.status) {
        ActiveWindowStatus.COMPLETE -> PremiumActiveTexts.STATUS_COMPLETE
        ActiveWindowStatus.INCOMPLETE -> PremiumActiveTexts.STATUS_INCOMPLETE
        ActiveWindowStatus.OVERLAP_UNRESOLVED -> PremiumActiveTexts.STATUS_OVERLAP
        ActiveWindowStatus.UNAVAILABLE -> PremiumActiveTexts.STATUS_UNAVAILABLE
    }

    // ---------- D3 coverage ----------

    private fun coverageLine(document: Document, s: ActiveSummary) {
        val start = s.historyStart
        val end = s.historyEnd
        val range = if (s.status == StudyHistoryStatus.AVAILABLE && start != null && end != null)
            PremiumReportFormat.inclusiveRange(start, end) else PremiumActiveTexts.NO_DATA
        document.add(Paragraph().apply {
            add(Phrase("${PremiumActiveTexts.HISTORY_AVAILABLE} ", PremiumReportTheme.bold()))
            add(Phrase(range, PremiumReportTheme.body()))
        })
        if (lacksHistory(s)) note(document, PremiumActiveTexts.PARTIAL_HISTORY)
    }

    /**
     * The partial-history sentence is shown only when the latest window is INCOMPLETE because the history starts
     * after the window start (less than 12 months of history); overlap-blocked or unavailable windows have their
     * own notes instead.
     */
    private fun lacksHistory(s: ActiveSummary): Boolean {
        if (s.status != StudyHistoryStatus.AVAILABLE) return false
        val latest = s.windows.firstOrNull { it.kind == ActiveWindowKind.LATEST_12M } ?: return false
        val start = s.historyStart ?: return false
        return latest.status == ActiveWindowStatus.INCOMPLETE && start > latest.start
    }

    // ---------- D4 windows ----------

    private fun windowTable(s: ActiveSummary): PdfPTable {
        val t = table(floatArrayOf(2.3f, 1.3f, 1.1f, 1f, 1f, 1f, 1f, 1f, 1f, 1.35f))
        t.addCell(header(PremiumActiveTexts.COL_PERIOD, Element.ALIGN_LEFT))
        t.addCell(header(PremiumActiveTexts.COL_STATUS, Element.ALIGN_LEFT))
        t.addCell(header(PremiumActiveTexts.COL_COVERED_DAYS))
        periodHeaders(t, " (kWh)")
        t.addCell(header(PremiumActiveTexts.COL_TOTAL_KWH))
        if (s.windows.isEmpty()) {
            t.addCell(cell(PremiumActiveTexts.NO_DATA, align = Element.ALIGN_LEFT, colspan = 10))
            return t
        }
        s.windows.forEach { w ->
            t.addCell(cell("${windowName(w)}\n${PremiumReportFormat.inclusiveRange(w.start, w.endExclusive)}", align = Element.ALIGN_LEFT))
            t.addCell(cell(statusText(w), align = Element.ALIGN_LEFT))
            when (w.status) {
                ActiveWindowStatus.COMPLETE, ActiveWindowStatus.INCOMPLETE -> {
                    t.addCell(cell("${w.coveredDays} / ${w.windowDays}"))
                    val shares = w.shares?.let { cells(it) }
                    cells(w.periodTotals).forEachIndexed { i, v ->
                        val share = shares?.get(i)?.let { "\n${PremiumReportFormat.percent(it)}" } ?: ""
                        t.addCell(cell(kwh(v) + share))
                    }
                    val total = if (w.status == ActiveWindowStatus.COMPLETE) kwh(w.total)
                    else "${PremiumActiveTexts.PARTIAL_PREFIX} ${kwh(w.partialTotal)}"
                    t.addCell(cell(total, font = tableBold))
                }
                ActiveWindowStatus.OVERLAP_UNRESOLVED ->
                    t.addCell(cell(PremiumActiveTexts.OVERLAP_NO_FIGURE, align = Element.ALIGN_LEFT, colspan = 8))
                ActiveWindowStatus.UNAVAILABLE ->
                    t.addCell(cell(PremiumActiveTexts.NO_DATA, align = Element.ALIGN_LEFT, colspan = 8))
            }
        }
        return t
    }

    private fun notes(document: Document, s: ActiveSummary, history: StudyHistory) {
        val change = s.changePct
        if (change != null) {
            document.add(Paragraph("${PremiumActiveTexts.YOY} ${PremiumReportFormat.signedPercent(change)}", PremiumReportTheme.bold()).apply { spacingBefore = 6f })
        } else {
            val latest = s.windows.firstOrNull { it.kind == ActiveWindowKind.LATEST_12M }
            val previous = s.windows.firstOrNull { it.kind == ActiveWindowKind.PREVIOUS_12M }
            val reason = when {
                latest?.status == ActiveWindowStatus.OVERLAP_UNRESOLVED -> PremiumActiveTexts.YOY_LATEST_OVERLAP
                previous?.status == ActiveWindowStatus.OVERLAP_UNRESOLVED -> PremiumActiveTexts.YOY_PREVIOUS_OVERLAP
                else -> when (s.changeReason) {
                ActiveSummaryCalculator.LATEST_INCOMPLETE -> PremiumActiveTexts.YOY_LATEST_INCOMPLETE
                ActiveSummaryCalculator.PREVIOUS_INCOMPLETE -> PremiumActiveTexts.YOY_PREVIOUS_INCOMPLETE
                ActiveSummaryCalculator.PREVIOUS_ZERO -> PremiumActiveTexts.YOY_PREVIOUS_ZERO
                else -> PremiumActiveTexts.YOY_NO_DATA
                }
            }
            note(document, "${PremiumActiveTexts.YOY_UNAVAILABLE} ($reason).")
        }
        s.windows.forEach { w ->
            w.overlapDisclosures.forEach { o ->
                val zero = listOf(o.firstIndex, o.secondIndex).any { i ->
                    history.intervals.getOrNull(i)?.active?.let { cells(it).all { c -> c == 0.0 } } == true
                }
                val tail = if (zero) PremiumActiveTexts.OVERLAP_ZERO_NOTE else PremiumActiveTexts.OVERLAP_BLOCKING_NOTE
                note(document, "${PremiumActiveTexts.OVERLAPPED_READINGS} ${PremiumReportFormat.inclusiveRange(o.start, o.endExclusive, "–")}: $tail")
            }
        }
    }

    // ---------- D5 / D6 charts ----------

    private fun distributionReason(latest: ActiveWindowSummary?) = when {
        latest?.status == ActiveWindowStatus.OVERLAP_UNRESOLVED -> PremiumActiveTexts.DISTRIBUTION_OVERLAP
        latest?.status == ActiveWindowStatus.COMPLETE -> PremiumActiveTexts.DISTRIBUTION_ZERO
        else -> PremiumActiveTexts.DISTRIBUTION_NOT_COMPLETE
    }

    private fun chart(document: Document, writer: PdfWriter, model: ActiveChartModel) {
        val holder = PdfPTable(1).apply {
            widthPercentage = 100f
            keepTogether = true
            addCell(PdfPCell(ActiveChartPainter.image(writer, model), false).apply {
                border = Rectangle.NO_BORDER
                setPadding(0f)
                horizontalAlignment = Element.ALIGN_LEFT
            })
        }
        document.add(holder)
    }

    // ---------- D7 high intervals ----------

    private fun highIntervals(document: Document, writer: PdfWriter, s: ActiveSummary) {
        heading(document, writer, PremiumActiveTexts.HIGH_TITLE)
        val first = s.highIntervals.firstOrNull()
        val source = first?.let { h -> s.windows.firstOrNull { h.index in it.contained } }
        if (first == null || source == null) {
            note(document, PremiumActiveTexts.HIGH_EMPTY)
            return
        }
        note(document, "${PremiumActiveTexts.HIGH_CAPTION} ${windowLabel(source)} (${statusText(source)})")
        val t = table(floatArrayOf(1.2f, 1.2f, 0.7f, 1f, 1f, 1.3f))
        t.addCell(header(PremiumActiveTexts.COL_FROM, Element.ALIGN_LEFT))
        t.addCell(header(PremiumActiveTexts.COL_TO, Element.ALIGN_LEFT))
        listOf(PremiumActiveTexts.COL_DAYS, PremiumActiveTexts.COL_KWH, PremiumActiveTexts.COL_KWH_DAY).forEach { t.addCell(header(it)) }
        t.addCell(header(PremiumActiveTexts.COL_DOMINANT, Element.ALIGN_CENTER))
        s.highIntervals.forEach { h ->
            t.addCell(cell(PremiumReportFormat.date(h.start), align = Element.ALIGN_LEFT))
            t.addCell(cell(PremiumReportFormat.date(h.endExclusive.minusDays(1)), align = Element.ALIGN_LEFT))
            t.addCell(cell(h.days.toString()))
            t.addCell(cell(PremiumReportFormat.kwh(h.totalKwh)))
            t.addCell(cell(PremiumReportFormat.decimal1(h.kwhPerDay)))
            t.addCell(cell(h.dominantPeriod?.let { "P$it" } ?: PremiumActiveTexts.MISSING, align = Element.ALIGN_CENTER))
        }
        document.add(t)
    }

    // ---------- D8 comparisons ----------

    private fun comparisons(document: Document, writer: PdfWriter, s: ActiveSummary, latest: ActiveWindowSummary?) {
        heading(document, writer, PremiumActiveTexts.COMPARISON_TITLE)
        if (latest?.status == ActiveWindowStatus.COMPLETE) note(document, PremiumActiveTexts.comparisonNote(windowLabel(latest)))
        // "No history" only when there truly is none; a latest window empty only because its rows cross the
        // window boundary is reported as not complete (true), never as "no history".
        val hasActiveHistory = s.status == StudyHistoryStatus.AVAILABLE && s.historyStart != null
        val notComparable = when {
            latest?.status == ActiveWindowStatus.OVERLAP_UNRESOLVED -> PremiumActiveTexts.NOT_COMPARABLE_OVERLAP
            latest == null || (latest.status == ActiveWindowStatus.UNAVAILABLE && !hasActiveHistory) ->
                PremiumActiveTexts.NOT_COMPARABLE_NO_HISTORY
            else -> PremiumActiveTexts.NOT_COMPARABLE
        }
        s.comparisons.forEach { c -> comparison(document, writer, c, notComparable) }
    }

    private fun comparison(document: Document, writer: PdfWriter, c: AnnualSourceComparison, notComparable: String) {
        if (writer.getVerticalPosition(true) - document.bottom() < 190f) document.newPage()
        val label = if (c.source == AnnualSource.SIPS_SNAPSHOT) PremiumActiveTexts.SOURCE_SIPS else PremiumActiveTexts.SOURCE_LEGACY
        document.add(Paragraph(label, PremiumReportTheme.bold()).apply { spacingBefore = 8f; spacingAfter = 2f; keepTogether = true })
        when (c.status) {
            ComparisonStatus.SOURCE_UNAVAILABLE -> { note(document, PremiumActiveTexts.SOURCE_UNAVAILABLE); return }
            ComparisonStatus.NOT_COMPARABLE -> note(document, notComparable)
            ComparisonStatus.COMPARED -> Unit
        }
        val compared = c.status == ComparisonStatus.COMPARED
        val t = table(floatArrayOf(1f, 1.3f, 1.3f, 1.3f, 1.2f))
        t.addCell(header(PremiumActiveTexts.COL_PERIOD, Element.ALIGN_LEFT))
        t.addCell(header("${PremiumActiveTexts.COL_SOURCE} (kWh)"))
        t.addCell(header("${PremiumActiveTexts.COL_HISTORY} (kWh)"))
        t.addCell(header(PremiumActiveTexts.COL_DIFF_KWH))
        t.addCell(header(PremiumActiveTexts.COL_DIFF_PCT))
        (c.periods + c.total).forEach { v -> comparisonRow(t, v, compared) }
        document.add(t)
    }

    private fun comparisonRow(t: PdfPTable, v: ValueComparison, compared: Boolean) {
        val bold = v.period == null
        val font = if (bold) tableBold else tableFont
        t.addCell(cell(v.period?.let { "P$it" } ?: PremiumActiveTexts.TOTAL, font, Element.ALIGN_LEFT))
        t.addCell(cell(kwh(v.sourceKwh), font))
        t.addCell(cell(if (compared) kwh(v.historyKwh) else PremiumActiveTexts.MISSING, font))
        t.addCell(cell(v.differenceKwh?.takeIf { compared }?.let { PremiumReportFormat.signedKwh(it) } ?: PremiumActiveTexts.MISSING, font))
        t.addCell(cell(v.differencePct?.takeIf { compared }?.let { PremiumReportFormat.signedPercent(it) } ?: PremiumActiveTexts.MISSING, font))
    }

    // ---------- D9 readings table ----------

    private fun readingsTable(document: Document, writer: PdfWriter, history: StudyHistory, latest: ActiveWindowSummary?) {
        if (history.intervals.isEmpty()) return
        heading(document, writer, PremiumActiveTexts.READINGS_TABLE_TITLE)
        val crossing = latest?.crossing.orEmpty().toSet()
        val t = table(floatArrayOf(1.35f, 1.25f, 0.6f, 1f, 1f, 1f, 1f, 1f, 1f, 1.1f))
        t.addCell(header(PremiumActiveTexts.COL_FROM, Element.ALIGN_LEFT))
        t.addCell(header(PremiumActiveTexts.COL_TO, Element.ALIGN_LEFT))
        t.addCell(header(PremiumActiveTexts.COL_DAYS))
        periodHeaders(t)
        t.addCell(header(PremiumActiveTexts.TOTAL))
        history.intervals.forEachIndexed { i, r ->
            val days = ChronoUnit.DAYS.between(r.startDate, r.endDate)
            val to = if (days > 0) r.endDate.minusDays(1) else r.endDate
            val mark = if (i in crossing) "*" else ""
            t.addCell(cell(PremiumReportFormat.date(r.startDate) + mark, align = Element.ALIGN_LEFT))
            t.addCell(cell(PremiumReportFormat.date(to), align = Element.ALIGN_LEFT))
            t.addCell(cell(days.toString()))
            val values = cells(r.active)
            values.forEach { t.addCell(cell(kwh(it))) }
            val present = values.filterNotNull()
            t.addCell(cell(if (present.isEmpty()) PremiumActiveTexts.MISSING else PremiumReportFormat.kwh(present.sum()), tableBold))
        }
        document.add(t)
        if (crossing.isNotEmpty()) note(document, PremiumActiveTexts.CROSSING_NOTE)
        note(document, PremiumActiveTexts.DATES_NOTE)
    }
}
