package com.bm.backend.models

/** What the report does with an overlapping pair under the current rules. */
enum class HistoryOverlapEffect(val code: String) {
    /** ACTIVE/REACTIVE, no all-zero row: no firm total for the window (`overlap_unresolved`). */
    BLOCKS_TOTAL("blocks_total"),
    /** ACTIVE/REACTIVE, one row is all 0.0: treated as value-neutral, total still shown (A1/R2). */
    NEUTRAL_ZERO_ROW("neutral_zero_row"),
    /** MAX_DEMAND: peak still shown, mean of maxima withheld (M2/M4). */
    HIDES_DEMAND_MEAN("hides_demand_mean")
}
