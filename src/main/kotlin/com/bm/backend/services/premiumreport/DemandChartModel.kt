package com.bm.backend.services.premiumreport

import com.bm.backend.models.DemandPeriodStatistic
import com.bm.backend.models.DemandSeriesPoint
import com.bm.backend.models.PeriodValues
import com.bm.backend.models.TariffPeriodProfile
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Pure geometry of the demanded-power charts (spec report-demand-page V3/V4). Both factories return the shared
 * [ActiveChartModel] so [ActiveChartPainter] draws them. Values are kW exactly as the demand summary gives them;
 * the only presentation-only figure is the per-reading highest P1–P6 value of [readings].
 */
object DemandChartModel {
    private fun cells(v: PeriodValues) = listOf(v.p1, v.p2, v.p3, v.p4, v.p5, v.p6)

    /**
     * Horizontal bars (tariff power periods, default P1–P6) of the highest recorded value with a neutral vertical contracted-power line per period (V3).
     * Null peak ⇒ no-data band; recorded 0 ⇒ zero marker; no line when contracted power is missing.
     */
    fun comparison(periods: List<DemandPeriodStatistic>, powerPeriods: List<Int> = TariffPeriodProfile.ALL_PERIODS): ActiveChartModel {
        val band = 22.0
        val plotLeft = 96.0
        val plotBottom = 18.0
        val plotWidth = ActiveChartModel.WIDTH - plotLeft - 16.0
        val plotHeight = band * powerPeriods.size
        val ordered = powerPeriods.map { n -> periods.firstOrNull { it.period == n } }
        val axisMax = ActiveChartModel.niceCeiling(
            (ordered.mapNotNull { it?.peakKw } + ordered.mapNotNull { it?.contractedKw }).maxOrNull() ?: 0.0
        )
        fun y(i: Int) = plotBottom + plotHeight - band * (i + 1)
        val bars = ordered.mapIndexed { i, p ->
            val peak = p?.peakKw
            val label = "P${powerPeriods[i]} " + (peak?.let { "${PremiumReportFormat.number(it)} ${PremiumDemandTexts.UNIT}" } ?: PremiumDemandTexts.MISSING)
            val style = when {
                peak == null -> ChartBarStyle.NO_DATA
                peak == 0.0 -> ChartBarStyle.ZERO_MARKER
                else -> ChartBarStyle.FILLED
            }
            ChartBar(null, powerPeriods[i], peak, label, plotLeft, y(i) + band * 0.2,
                if (peak == null) plotWidth else peak / axisMax * plotWidth, band * 0.6, style)
        }
        val markers = ordered.mapIndexedNotNull { i, p ->
            p?.contractedKw?.let { c -> ChartMarker(powerPeriods[i], c, plotLeft + c / axisMax * plotWidth, y(i) + band * 0.05, band * 0.9) }
        }
        return ActiveChartModel(
            width = ActiveChartModel.WIDTH, height = plotBottom + plotHeight + 4.0,
            plotLeft = plotLeft, plotBottom = plotBottom, plotWidth = plotWidth, plotHeight = plotHeight,
            horizontal = true, axisMax = axisMax, unit = PremiumDemandTexts.UNIT,
            valueTicks = ActiveChartModel.ticks(axisMax, plotWidth) { "${ActiveChartModel.tickNumber(it)} ${PremiumDemandTexts.UNIT}" },
            categoryTicks = emptyList(), bars = bars, totalRows = powerPeriods.size, shownRows = powerPeriods.size, markers = markers
        )
    }

    /**
     * One vertical bar per series row (contained latest-window rows, date order) on a true date axis (V4):
     * height = highest non-null P1–P6 value of that row in kW (not per day), width = days. Zero ⇒ zero marker,
     * overlapping rows outlined. The most recent [ActiveChartModel.READINGS_CAP] rows are shown. Null when empty.
     */
    fun readings(series: List<DemandSeriesPoint>): ActiveChartModel? {
        val dated = series.filter { it.days > 0 }
            .sortedWith(compareBy<DemandSeriesPoint>({ it.start }, { it.endExclusive }, { it.index }))
        if (dated.isEmpty()) return null
        val shown = dated.takeLast(ActiveChartModel.READINGS_CAP)
        val start = shown.minOf { it.start }
        val end = shown.maxOf { it.endExclusive }
        val span = ChronoUnit.DAYS.between(start, end).toDouble()

        val plotLeft = 46.0
        val plotBottom = 30.0
        val plotWidth = ActiveChartModel.WIDTH - plotLeft - 10.0
        val plotHeight = 150.0
        val maxima = shown.map { p -> cells(p.values).filterNotNull().maxOrNull() }
        val axisMax = ActiveChartModel.niceCeiling(maxima.filterNotNull().maxOrNull() ?: 0.0)
        fun x(d: LocalDate) = plotLeft + ChronoUnit.DAYS.between(start, d) / span * plotWidth

        val bars = shown.mapIndexed { i, p ->
            val v = maxima[i]
            val style = when {
                v == null -> ChartBarStyle.NO_DATA
                v == 0.0 -> ChartBarStyle.ZERO_MARKER
                p.inOverlap -> ChartBarStyle.OUTLINED
                else -> ChartBarStyle.FILLED
            }
            val label = "${PremiumReportFormat.inclusiveRange(p.start, p.endExclusive)}: " +
                (v?.let { "${PremiumReportFormat.number(it)} ${PremiumDemandTexts.UNIT}" } ?: PremiumDemandTexts.MISSING)
            ChartBar(p.index, null, v, label, x(p.start), plotBottom, p.days / span * plotWidth,
                if (v == null) plotHeight else v / axisMax * plotHeight, style)
        }
        return ActiveChartModel(
            width = ActiveChartModel.WIDTH, height = plotBottom + plotHeight + 16.0,
            plotLeft = plotLeft, plotBottom = plotBottom, plotWidth = plotWidth, plotHeight = plotHeight,
            horizontal = false, axisMax = axisMax, unit = PremiumDemandTexts.UNIT,
            valueTicks = ActiveChartModel.ticks(axisMax, plotHeight) { ActiveChartModel.tickNumber(it) },
            categoryTicks = ActiveChartModel.monthTicks(start, end)
                .map { ChartTick(x(it) - plotLeft, ActiveChartModel.monthLabel(it)) },
            bars = bars, totalRows = dated.size, shownRows = shown.size
        )
    }
}
