package com.bm.backend.services.premiumreport

import com.bm.backend.models.PeriodValues
import com.bm.backend.models.ReactiveSeriesPoint
import com.bm.backend.models.ReactiveWindowStatus
import com.bm.backend.models.ReactiveWindowSummary
import com.bm.backend.models.TariffPeriodProfile
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Pure geometry of the reactive-energy charts (spec report-reactive-page V2–V4). Both factories return the shared
 * [ActiveChartModel] so [ActiveChartPainter] draws them unchanged. Values come only from the reactive summary.
 */
object ReactiveChartModel {
    private fun cells(v: PeriodValues) = listOf(v.p1, v.p2, v.p3, v.p4, v.p5, v.p6)

    /** Horizontal share bars of the tariff's [periods] (default P1–P6) of a COMPLETE window with shares, labelled in kVArh; null otherwise (V4). */
    fun distribution(window: ReactiveWindowSummary, periods: List<Int> = TariffPeriodProfile.ALL_PERIODS): ActiveChartModel? {
        val shares = window.shares ?: return null
        if (window.status != ReactiveWindowStatus.COMPLETE) return null
        val totals = periods.map { cells(window.periodTotals)[it - 1] }
        val values = periods.map { cells(shares)[it - 1] }
        val band = 22.0
        val plotLeft = 150.0
        val plotBottom = 18.0
        val plotWidth = ActiveChartModel.WIDTH - plotLeft - 16.0
        val plotHeight = band * periods.size
        val axisMax = ActiveChartModel.niceCeiling(values.filterNotNull().maxOrNull() ?: 0.0)
        val bars = values.mapIndexed { i, share ->
            val y = plotBottom + plotHeight - band * (i + 1) + band * 0.2
            val total = totals[i]
            val label = "P${periods[i]} " + if (share == null || total == null) PremiumReactiveTexts.MISSING
            else "${PremiumReportFormat.kwh(total)} ${PremiumReactiveTexts.UNIT} (${PremiumReportFormat.percent(share)})"
            ChartBar(null, periods[i], share, label, plotLeft, y, (share ?: 0.0) / axisMax * plotWidth, band * 0.6,
                if (share == null) ChartBarStyle.NO_DATA else ChartBarStyle.FILLED)
        }
        return ActiveChartModel(
            width = ActiveChartModel.WIDTH, height = plotBottom + plotHeight + 4.0,
            plotLeft = plotLeft, plotBottom = plotBottom, plotWidth = plotWidth, plotHeight = plotHeight,
            horizontal = true, axisMax = axisMax, unit = "%",
            valueTicks = ActiveChartModel.ticks(axisMax, plotWidth) { "${ActiveChartModel.tickNumber(it)} %" },
            categoryTicks = emptyList(), bars = bars, totalRows = periods.size, shownRows = periods.size
        )
    }

    /**
     * One vertical bar per series row (contained latest-window rows, date order) on a true date axis (V3):
     * height = row kVArh ÷ days, width = days. Zero rows are zero markers, unresolved-overlap rows outlined.
     * The most recent [ActiveChartModel.READINGS_CAP] rows are shown. Null when the series is empty.
     */
    fun readings(series: List<ReactiveSeriesPoint>): ActiveChartModel? {
        val dated = series.filter { it.days > 0 }
            .sortedWith(compareBy<ReactiveSeriesPoint>({ it.start }, { it.endExclusive }, { it.index }))
        if (dated.isEmpty()) return null
        val shown = dated.takeLast(ActiveChartModel.READINGS_CAP)
        val start = shown.minOf { it.start }
        val end = shown.maxOf { it.endExclusive }
        val span = ChronoUnit.DAYS.between(start, end).toDouble()

        val plotLeft = 46.0
        val plotBottom = 30.0
        val plotWidth = ActiveChartModel.WIDTH - plotLeft - 10.0
        val plotHeight = 150.0
        val perDay = shown.map { p -> p.total?.let { it / p.days } }
        val axisMax = ActiveChartModel.niceCeiling(perDay.filterNotNull().maxOrNull() ?: 0.0)
        fun x(d: LocalDate) = plotLeft + ChronoUnit.DAYS.between(start, d) / span * plotWidth

        val bars = shown.mapIndexed { i, p ->
            val v = perDay[i]
            val style = when {
                v == null -> ChartBarStyle.NO_DATA
                v == 0.0 -> ChartBarStyle.ZERO_MARKER
                p.inUnresolvedOverlap -> ChartBarStyle.OUTLINED
                else -> ChartBarStyle.FILLED
            }
            val label = "${PremiumReportFormat.inclusiveRange(p.start, p.endExclusive)}: " +
                (v?.let { "${PremiumReportFormat.decimal1(it)} ${PremiumReactiveTexts.UNIT_PER_DAY}" } ?: PremiumReactiveTexts.MISSING)
            ChartBar(p.index, null, v, label, x(p.start), plotBottom, p.days / span * plotWidth,
                if (v == null) plotHeight else v / axisMax * plotHeight, style)
        }
        return ActiveChartModel(
            width = ActiveChartModel.WIDTH, height = plotBottom + plotHeight + 16.0,
            plotLeft = plotLeft, plotBottom = plotBottom, plotWidth = plotWidth, plotHeight = plotHeight,
            horizontal = false, axisMax = axisMax, unit = PremiumReactiveTexts.UNIT_PER_DAY,
            valueTicks = ActiveChartModel.ticks(axisMax, plotHeight) { PremiumReportFormat.decimal1(it) },
            categoryTicks = ActiveChartModel.monthTicks(start, end)
                .map { ChartTick(x(it) - plotLeft, ActiveChartModel.monthLabel(it)) },
            bars = bars, totalRows = dated.size, shownRows = shown.size
        )
    }
}
