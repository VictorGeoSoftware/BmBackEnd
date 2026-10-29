package com.bm.backend.models

/** Comparison of one annual source with the LATEST_12M history window (total and P1–P6). */
data class AnnualSourceComparison(
    val source: AnnualSource,
    val status: ComparisonStatus,
    val total: ValueComparison,
    val periods: List<ValueComparison>
)
