package com.bm.backend.services.premiumreport

/**
 * Reference line in chart coordinates (PDF points, origin bottom-left of the chart): a vertical line at [x] from [y]
 * spanning [height], marking [value] (for example the contracted power of [period]). Neutral, no verdict.
 */
data class ChartMarker(
    val period: Int,
    val value: Double,
    val x: Double,
    val y: Double,
    val height: Double
)
