package com.bm.backend.services.premiumreport

import com.bm.backend.models.PeriodValues
import java.awt.Color

/**
 * Original study's contracted-vs-average chart (feedback 2026-11-04): per power period one 100 % bar split into the
 * contracted power and the average demanded power shares, each labelled in kW. Periods without both values are
 * listed without a bar. Descriptive only: no recommendation.
 */
object ContractedAverageChart {
    val CONTRACTED_COLOR = Color(0xE1, 0x15, 0xB5)
    val AVERAGE_COLOR = Color(0xC5, 0xD7, 0x8D)

    fun model(contracted: PeriodValues, averages: PeriodValues, periods: List<Int>): ActiveChartModel {
        val band = 24.0
        val plotLeft = 40.0
        val plotBottom = 18.0
        val plotWidth = ActiveChartModel.WIDTH - plotLeft - 16.0
        val plotHeight = band * periods.size
        val c = listOf(contracted.p1, contracted.p2, contracted.p3, contracted.p4, contracted.p5, contracted.p6)
        val a = listOf(averages.p1, averages.p2, averages.p3, averages.p4, averages.p5, averages.p6)
        val bars = periods.flatMapIndexed { i, p ->
            val y = plotBottom + plotHeight - band * (i + 1) + band * 0.15
            val h = band * 0.7
            val cv = c[p - 1]
            val av = a[p - 1]
            if (cv == null || av == null || cv + av <= 0.0) {
                listOf(ChartBar(null, p, null, "P$p", plotLeft, y, 0.0, h, ChartBarStyle.NO_DATA))
            } else {
                val cw = cv / (cv + av) * plotWidth
                listOf(
                    ChartBar(null, p, cv, "P$p", plotLeft, y, cw, h, ChartBarStyle.FILLED, CONTRACTED_COLOR, "${PremiumReportFormat.number(cv)} kW"),
                    ChartBar(null, p, av, "", plotLeft + cw, y, plotWidth - cw, h, ChartBarStyle.FILLED, AVERAGE_COLOR, "${PremiumReportFormat.number(av)} kW")
                )
            }
        }
        return ActiveChartModel(
            width = ActiveChartModel.WIDTH, height = plotBottom + plotHeight + 4.0,
            plotLeft = plotLeft, plotBottom = plotBottom, plotWidth = plotWidth, plotHeight = plotHeight,
            horizontal = true, axisMax = 100.0, unit = "%",
            valueTicks = ActiveChartModel.ticks(100.0, plotWidth) { "${ActiveChartModel.tickNumber(it)} %" },
            categoryTicks = emptyList(), bars = bars, totalRows = periods.size, shownRows = periods.size
        )
    }
}
