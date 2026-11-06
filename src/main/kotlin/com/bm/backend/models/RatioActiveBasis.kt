package com.bm.backend.models

/** States which active-energy values form the denominator of a reactive/active quotient. */
enum class RatioActiveBasis {
    /** Dated history ACTIVE rows of the same latest 12-month window (never SIPS or legacy annual values). */
    HISTORY_ACTIVE_LATEST_12M
}
