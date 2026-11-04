package com.bm.backend.services.premiumreport

import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * Original study's "Registro de potencias demandadas" (feedback 2026-11-04): per reading, one thin bar per power
 * period with its maximeter value (kW, as published), in period colours, readings in table order, rotated end-date
 * labels and a legend. Zero and missing values draw no bar; the readings table shows them.
 */
object GroupedReadingsChart {
    private const val GROUP_FRACTION = 0.8
    private const val MAX_LABELS = 36
    private val LABEL_FORMAT = DateTimeFormatter.ofPattern("dd/MM/yy")

    fun model(slots: List<ReadingSlot>, periods: List<Int>, unit: String): ActiveChartModel {
        val plotLeft = 46.0
        val plotBottom = 48.0
        val plotWidth = ActiveChartModel.WIDTH - plotLeft - 10.0
        val plotHeight = 150.0
        val cells = slots.map { s -> listOf(s.values.p1, s.values.p2, s.values.p3, s.values.p4, s.values.p5, s.values.p6) }
        val max = cells.flatMap { c -> periods.mapNotNull { c[it - 1] } }.maxOrNull() ?: 0.0
        val axisMax = ActiveChartModel.niceCeiling(max)
        val slotWidth = plotWidth / slots.size.coerceAtLeast(1)
        val barWidth = slotWidth * GROUP_FRACTION / periods.size.coerceAtLeast(1)

        val bars = slots.indices.flatMap { i ->
            val left = plotLeft + slotWidth * i + slotWidth * (1 - GROUP_FRACTION) / 2
            val label = PremiumReportFormat.inclusiveRange(slots[i].start, slots[i].endExclusive)
            periods.mapIndexedNotNull { k, p ->
                val v = cells[i][p - 1]?.takeIf { it > 0.0 } ?: return@mapIndexedNotNull null
                ChartBar(i, p, v, label, left + barWidth * k, plotBottom, barWidth, v / axisMax * plotHeight, ChartBarStyle.FILLED)
            }
        }
        val step = Math.ceil(slots.size / MAX_LABELS.toDouble()).toInt().coerceAtLeast(1)
        val ticks = slots.indices.filter { (slots.size - 1 - it) % step == 0 }.map { i ->
            val s = slots[i]
            val to = if (ChronoUnit.DAYS.between(s.start, s.endExclusive) > 0) s.endExclusive.minusDays(1) else s.endExclusive
            ChartTick(slotWidth * i + slotWidth / 2, to.format(LABEL_FORMAT), rotated = true)
        }
        return ActiveChartModel(
            width = ActiveChartModel.WIDTH, height = plotBottom + plotHeight + 30.0,
            plotLeft = plotLeft, plotBottom = plotBottom, plotWidth = plotWidth, plotHeight = plotHeight,
            horizontal = false, axisMax = axisMax, unit = unit,
            valueTicks = ActiveChartModel.ticks(axisMax, plotHeight) { ActiveChartModel.tickNumber(it) },
            categoryTicks = ticks, bars = bars, totalRows = slots.size, shownRows = slots.size, legend = periods
        )
    }
}
