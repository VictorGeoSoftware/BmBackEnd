package com.bm.backend.models

import java.time.LocalDate

/**
 * Recorded maximeter demand statistics for one period P1–P6 (kW, descriptive only).
 * [meanKw] is the mean of per-reading maxima (zeros included), not average power.
 * The contracted comparison is a plain difference and percentage; no verdict.
 */
data class DemandPeriodStatistic(
    val period: Int,
    val readings: Int,
    val peakKw: Double?,
    val peakIndex: Int?,
    val peakStart: LocalDate?,
    val peakEndExclusive: LocalDate?,
    val meanKw: Double?,
    val meanReason: String?,
    val contractedKw: Double?,
    val peakMinusContractedKw: Double?,
    val peakPctOfContracted: Double?,
    val comparisonReason: String?,
    val reason: String?
)
