package com.bm.backend.models

/** Comparable date-based 12-month windows anchored on the latest reading date. */
enum class ActiveWindowKind {
    LATEST_12M,
    PREVIOUS_12M
}
