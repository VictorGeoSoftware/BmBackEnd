package com.bm.backend.models

/**
 * One source value next to the history value ([period] null = total). Neither replaces the other.
 * `differenceKwh = history − source`; `differencePct` uses the source as denominator (null when 0/null);
 * `matches` when |difference| < 0.5 kWh.
 */
data class ValueComparison(
    val period: Int?,
    val sourceKwh: Double?,
    val historyKwh: Double?,
    val differenceKwh: Double?,
    val differencePct: Double?,
    val matches: Boolean?
)
