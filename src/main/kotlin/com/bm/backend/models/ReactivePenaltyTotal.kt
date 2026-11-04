package com.bm.backend.models

import java.time.LocalDate

/**
 * Penalty total of one [kind] window [start, endExclusive): sum of its contained readings' penalties, their P1–P5
 * reactive energy and the window power factor ΣA / √(ΣA² + ΣR²). Null figures carry a [reason].
 */
data class ReactivePenaltyTotal(
    val kind: ReactivePenaltyWindowKind,
    val start: LocalDate,
    val endExclusive: LocalDate,
    val readings: Int,
    val penaltyEur: Double?,
    val reactiveKvarh: Double?,
    val cosPhi: Double?,
    val reason: String?
)
