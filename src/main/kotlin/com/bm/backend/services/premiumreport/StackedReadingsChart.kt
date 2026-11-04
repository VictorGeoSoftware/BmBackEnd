package com.bm.backend.services.premiumreport

import java.time.temporal.ChronoUnit

/**
 * Per-reading chart of a readings table (feedback 2026-11-03, option a): one equal-width slot per source reading in
 * table order, its bar stacked by the tariff [periods] with the published cells (height = their sum, the table's
 * Total). Nothing is prorated or relabelled as a month. All-null readings are a hatched band, all-zero readings a
 * zero marker, readings in a disclosed overlap an unfilled outline. Drawn like the original study (feedback
 * 2026-11-04): one chart, every bar labelled with its inclusive end date (`dd/MM/yy`, rotated; every n-th bar beyond
 * [MAX_LABELS] readings).
 */
object StackedReadingsChart {
    private const val BAR_FRACTION = 0.6
    private const val MAX_LABELS = 36
    private val LABEL_FORMAT = java.time.format.DateTimeFormatter.ofPattern("dd/MM/yy")

    fun model(slots: List<ReadingSlot>, periods: List<Int>, unit: String): ActiveChartModel {
        val plotLeft = 46.0
        val plotBottom = 48.0
        val plotWidth = ActiveChartModel.WIDTH - plotLeft - 10.0
        val plotHeight = 150.0
        val totals = slots.map { total(it, periods) }
        val axisMax = ActiveChartModel.niceCeiling(totals.filterNotNull().maxOrNull() ?: 0.0)
        val slotWidth = plotWidth / slots.size.coerceAtLeast(1)
        val barWidth = slotWidth * BAR_FRACTION

        val bars = slots.flatMapIndexed { i, s ->
            val x = plotLeft + slotWidth * i + (slotWidth - barWidth) / 2
            val label = PremiumReportFormat.inclusiveRange(s.start, s.endExclusive)
            val total = totals[i]
            when {
                total == null -> listOf(ChartBar(i, null, null, label, x, plotBottom, barWidth, plotHeight, ChartBarStyle.NO_DATA))
                total == 0.0 -> listOf(ChartBar(i, null, 0.0, label, x, plotBottom, barWidth, 0.0, ChartBarStyle.ZERO_MARKER))
                s.overlap -> listOf(ChartBar(i, null, total, "$label*", x, plotBottom, barWidth, total / axisMax * plotHeight, ChartBarStyle.OUTLINED))
                else -> {
                    var y = plotBottom
                    periods.mapNotNull { p ->
                        val v = cell(s, p)?.takeIf { it > 0.0 } ?: return@mapNotNull null
                        val h = v / axisMax * plotHeight
                        ChartBar(i, p, v, label, x, y, barWidth, h, ChartBarStyle.FILLED).also { y += h }
                    }
                }
            }
        }
        // Every bar is labelled up to MAX_LABELS readings (a normal 24-month history); denser histories label every n-th, newest kept.
        val step = Math.ceil(slots.size / MAX_LABELS.toDouble()).toInt().coerceAtLeast(1)
        val categoryTicks = slots.indices.filter { (slots.size - 1 - it) % step == 0 }.map { i ->
            val s = slots[i]
            val to = if (ChronoUnit.DAYS.between(s.start, s.endExclusive) > 0) s.endExclusive.minusDays(1) else s.endExclusive
            ChartTick(slotWidth * i + slotWidth / 2, to.format(LABEL_FORMAT), rotated = true)
        }
        return ActiveChartModel(
            width = ActiveChartModel.WIDTH, height = plotBottom + plotHeight + 30.0,
            plotLeft = plotLeft, plotBottom = plotBottom, plotWidth = plotWidth, plotHeight = plotHeight,
            horizontal = false, axisMax = axisMax, unit = unit,
            valueTicks = ActiveChartModel.ticks(axisMax, plotHeight) { ActiveChartModel.tickNumber(it) },
            categoryTicks = categoryTicks, bars = bars, totalRows = slots.size, shownRows = slots.size,
            legend = periods
        )
    }

    private fun cell(s: ReadingSlot, p: Int): Double? =
        listOf(s.values.p1, s.values.p2, s.values.p3, s.values.p4, s.values.p5, s.values.p6)[p - 1]

    /** Sum of the published cells of [periods]; null when all are missing. */
    fun total(s: ReadingSlot, periods: List<Int>): Double? =
        periods.mapNotNull { cell(s, it) }.takeIf { it.isNotEmpty() }?.sum()
}
