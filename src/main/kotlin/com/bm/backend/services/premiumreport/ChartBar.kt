package com.bm.backend.services.premiumreport

/**
 * One bar in chart coordinates (PDF points, origin bottom-left of the chart). [value] is the plotted figure
 * (share % or kWh/day), null for a no-data band. [sourceIndex] is the history row, [period] the tariff period.
 */
data class ChartBar(
    val sourceIndex: Int?,
    val period: Int?,
    val value: Double?,
    val label: String,
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double,
    val style: ChartBarStyle
)
