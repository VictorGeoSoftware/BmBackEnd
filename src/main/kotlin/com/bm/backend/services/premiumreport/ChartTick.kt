package com.bm.backend.services.premiumreport

/** Axis tick at [position] (PDF points along the axis, chart coordinates) with its text label. */
data class ChartTick(
    val position: Double,
    val label: String
)
