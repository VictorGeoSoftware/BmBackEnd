package com.bm.backend.models

/** Totals windows of the reactive penalty estimate: latest 12 months, the 12 before them and the last 24 months. */
enum class ReactivePenaltyWindowKind {
    LAST_12M,
    PREVIOUS_12M,
    LAST_24M
}
