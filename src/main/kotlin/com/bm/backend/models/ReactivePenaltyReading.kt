package com.bm.backend.models

import java.time.LocalDate

/**
 * Estimated inductive reactive penalty of one reactive history row ([index]), in euros, unrounded. Null
 * [penaltyEur] means not calculable and [reason] says why (active or reactive cell missing in a penalised period).
 */
data class ReactivePenaltyReading(
    val index: Int,
    val start: LocalDate,
    val endExclusive: LocalDate,
    val penaltyEur: Double?,
    val reason: String?,
    val inUnresolvedOverlap: Boolean
)
