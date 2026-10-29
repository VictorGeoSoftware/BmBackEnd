package com.bm.backend.services.premiumreport

/** How a chart bar is drawn: filled, outlined (overlapping reading), a zero-height marker or a hatched no-data band. */
enum class ChartBarStyle {
    FILLED,
    OUTLINED,
    ZERO_MARKER,
    NO_DATA
}
