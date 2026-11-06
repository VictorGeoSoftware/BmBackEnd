package com.bm.backend.utils

import com.bm.backend.models.DemandRangeAverages
import com.bm.backend.models.DemandSeriesPoint

/**
 * Arithmetic mean of non-zero maximeter readings per period over the 24-month series (original study's rule). A
 * period whose recorded readings are all 0 averages 0; a period without readings has no mean.
 */
object DemandRangeCalculator {
    fun averages(series: List<DemandSeriesPoint>): DemandRangeAverages {
        val recorded = (0 until 6).map { p -> series.mapNotNull { FamilyWindowAggregator.cells(it.values)[p] } }
        val nonZero = recorded.map { v -> v.filter { it > 0.0 } }
        return DemandRangeAverages(
            means = FamilyWindowAggregator.values(recorded.indices.map { p ->
                when {
                    nonZero[p].isNotEmpty() -> nonZero[p].sum() / nonZero[p].size
                    recorded[p].isNotEmpty() -> 0.0
                    else -> null
                }
            }),
            counts = nonZero.map { it.size }
        )
    }
}
