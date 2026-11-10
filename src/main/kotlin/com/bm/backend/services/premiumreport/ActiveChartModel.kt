package com.bm.backend.services.premiumreport

import com.bm.backend.models.ActiveWindowSummary
import com.bm.backend.models.HistoryCoverage
import com.bm.backend.models.MeasurementFamily
import com.bm.backend.models.StudyHistory
import com.bm.backend.models.StudyHistoryStatus
import com.bm.backend.models.TariffPeriodProfile
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/**
 * Pure geometry of one active-energy chart (no drawing, no clock, no randomness). Values come only from the
 * calculators' output or the source rows; nothing is projected or reinterpreted as months.
 * Horizontal charts plot values along x (value ticks on x, categories on y); vertical charts the opposite.
 */
data class ActiveChartModel(
    val width: Double,
    val height: Double,
    val plotLeft: Double,
    val plotBottom: Double,
    val plotWidth: Double,
    val plotHeight: Double,
    val horizontal: Boolean,
    val axisMax: Double,
    val unit: String,
    val valueTicks: List<ChartTick>,
    val categoryTicks: List<ChartTick>,
    val bars: List<ChartBar>,
    val totalRows: Int,
    val shownRows: Int,
    /** Reference lines (demanded-power page); empty for every other chart, which therefore draws unchanged. */
    val markers: List<ChartMarker> = emptyList(),
    /** Tariff periods shown as a colour legend above the plot (stacked per-reading charts); empty elsewhere. */
    val legend: List<Int> = emptyList()
) {
    companion object {
        const val WIDTH = 511.0
        private const val VALUE_DIVISIONS = 5

        /** Smallest 1/2/5×10ⁿ ≥ [value]; 1 when the value is not positive. */
        fun niceCeiling(value: Double): Double {
            if (value <= 0.0 || !value.isFinite()) return 1.0
            val magnitude = 10.0.pow(floor(log10(value)))
            val fraction = value / magnitude
            val nice = listOf(1.0, 2.0, 5.0, 10.0).first { fraction <= it + 1e-9 }
            return nice * magnitude
        }

        internal fun ticks(axisMax: Double, length: Double, label: (Double) -> String) =
            (0..VALUE_DIVISIONS).map { i ->
                val v = axisMax * i / VALUE_DIVISIONS
                ChartTick(length * i / VALUE_DIVISIONS, label(v))
            }

        internal fun tickNumber(v: Double): String =
            if (v == floor(v)) PremiumReportFormat.kwh(v) else PremiumReportFormat.number(v)

        /** Share bars of available consumption in a COMPLETE or INCOMPLETE window; never projected (D5). */
        fun distribution(window: ActiveWindowSummary, periods: List<Int> = TariffPeriodProfile.ALL_PERIODS): ActiveChartModel? {
            val shares = window.shares ?: return null
            if (window.recordedTotal?.let { it > 0 } != true) return null
            val allTotals = window.periodTotals.let { listOf(it.p1, it.p2, it.p3, it.p4, it.p5, it.p6) }
            val allShares = shares.let { listOf(it.p1, it.p2, it.p3, it.p4, it.p5, it.p6) }
            val totals = periods.map { allTotals[it - 1] }
            val values = periods.map { allShares[it - 1] }
            val band = 22.0
            val plotLeft = 150.0
            val plotBottom = 18.0
            val plotWidth = WIDTH - plotLeft - 16.0
            val plotHeight = band * periods.size
            val axisMax = niceCeiling(values.filterNotNull().maxOrNull() ?: 0.0)
            val bars = values.mapIndexed { i, share ->
                val y = plotBottom + plotHeight - band * (i + 1) + band * 0.2
                val label = "P${periods[i]} " + (totals[i]?.let { "${PremiumReportFormat.kwh(it)} kWh" } ?: "—") +
                    (share?.let { " (${PremiumReportFormat.percent(it)})" } ?: "")
                val w = (share ?: 0.0) / axisMax * plotWidth
                ChartBar(null, periods[i], share, label, plotLeft, y, w, band * 0.6,
                    if (share == null) ChartBarStyle.NO_DATA else ChartBarStyle.FILLED)
            }
            return ActiveChartModel(
                width = WIDTH, height = plotBottom + plotHeight + 4.0,
                plotLeft = plotLeft, plotBottom = plotBottom, plotWidth = plotWidth, plotHeight = plotHeight,
                horizontal = true, axisMax = axisMax, unit = "%",
                valueTicks = ticks(axisMax, plotWidth) { "${tickNumber(it)} %" },
                categoryTicks = emptyList(), bars = bars, totalRows = periods.size, shownRows = periods.size
            )
        }

        internal fun monthLabel(d: LocalDate) = "%02d/%d".format(d.monthValue, d.year)

        /** First-of-month dates inside [start, end], every 1/2/3/4/6/12 months so that at most ~10 labels appear. */
        internal fun monthTicks(start: LocalDate, end: LocalDate): List<LocalDate> {
            val months = ChronoUnit.MONTHS.between(start.withDayOfMonth(1), end.withDayOfMonth(1)).coerceAtLeast(1)
            val step = listOf(1, 2, 3, 4, 6, 12).firstOrNull { ceil(months.toDouble() / it) <= 10 } ?: 12
            val result = mutableListOf<LocalDate>()
            var d = start.withDayOfMonth(1).let { if (it < start) it.plusMonths(1) else it }
            while (d <= end) {
                if ((d.monthValue - 1) % step == 0) result += d
                d = d.plusMonths(1)
            }
            return result
        }
    }
}
