package com.bm.backend.models

/**
 * Arithmetic mean per power period of the maximeter readings of the last 24 months, excluding zero readings like the
 * original study; [counts] are the readings averaged per period (index 0 = P1). Null mean when none.
 */
data class DemandRangeAverages(
    val means: PeriodValues,
    val counts: List<Int>
)
