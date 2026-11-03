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
}
