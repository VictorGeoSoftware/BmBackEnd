package com.bm.backend.models

import java.time.LocalDate

/** Actual historical coverage of one measurement family (dates only; no value aggregates). */
data class FamilyCoverage(
    val startDate: LocalDate?,
    val endDate: LocalDate?,
    val usableIntervals: Int,
    val missingIntervals: List<Int>,
    val zeroLengthIntervals: List<Int>,
    val nullValues: Int,
    val coveredDays: Long,
    val gaps: List<CoverageGap>,
    val overlaps: List<CoverageOverlap>,
    val hasUnresolvedOverlaps: Boolean,
    val windows: List<WindowCoverage>
)
