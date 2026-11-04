package com.bm.backend.models

/**
 * Estimated inductive reactive penalty (scope change approved 2026-11-04). [applicable] is false for tariffs the
 * regulated reactive term does not cover; then the lists are empty.
 */
data class ReactivePenaltySummary(
    val applicable: Boolean,
    val readings: List<ReactivePenaltyReading>,
    val totals: List<ReactivePenaltyTotal>
) {
    companion object {
        val NOT_APPLICABLE = ReactivePenaltySummary(false, emptyList(), emptyList())
    }
}
