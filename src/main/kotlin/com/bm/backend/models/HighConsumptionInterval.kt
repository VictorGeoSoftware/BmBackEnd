package com.bm.backend.models

import java.time.LocalDate

/** A source row ranked by average daily active energy (no threshold, no advice). */
data class HighConsumptionInterval(
    val index: Int,
    val start: LocalDate,
    val endExclusive: LocalDate,
    val days: Long,
    val totalKwh: Double,
    val kwhPerDay: Double,
    val dominantPeriod: Int?
)
