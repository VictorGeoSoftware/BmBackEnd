package com.bm.backend.services.premiumreport

import com.bm.backend.models.DemandPeriodStatistic
import com.bm.backend.models.PeriodValues
import com.bm.backend.models.TariffPeriodProfile

/**
 * Pure geometry of the maximum-vs-contracted demand chart (spec report-demand-page V3). Returns the shared
 * [ActiveChartModel] so [ActiveChartPainter] draws it; values are kW exactly as the demand summary gives them. The
 * per-reading chart is [GroupedReadingsChart] (spec report-original-fidelity).
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
}
