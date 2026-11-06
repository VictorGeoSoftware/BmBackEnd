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
    val style: ChartBarStyle,
    /** Fill overriding the period colour (contracted-vs-average chart); null elsewhere. */
    val color: java.awt.Color? = null,
    /** Text centred inside the bar (contracted-vs-average chart); null elsewhere. */
    val innerLabel: String? = null
)
