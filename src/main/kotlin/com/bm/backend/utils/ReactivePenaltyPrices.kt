package com.bm.backend.utils

import java.time.LocalDate

/**
 * Regulated inductive reactive energy term (Resolución CNMC 18/12/2025, BOE 22/12/2025, BOE-A-2025-26348,
 * apartado primero punto 3 y anexo III), applicable from 01/01/2026 to 3.0TD and 6.1TD in P1–P5. Confirmed by the
 * user's colleague on 2026-11-04. 6.2–6.4TD are assumed to share the values (see docs/risks-and-open-questions.md).
 */
object ReactivePenaltyPrices {
    const val BOE_REFERENCE = "BOE-A-2025-26348"
    val VALID_FROM: LocalDate = LocalDate.of(2026, 1, 1)

    /** €/kVArh of excess when 0,80 ≤ cos φ < 0,95. */
    const val PRICE_MEDIUM = 0.041554
    /** €/kVArh of excess when cos φ < 0,80. */
    const val PRICE_HIGH = 0.062332

    const val FREE_RATIO = 0.33
    const val COS_PHI_FREE = 0.95
    const val COS_PHI_HIGH = 0.80

    val PERIODS: List<Int> = (1..5).toList()
    val TARIFFS: Set<String> = setOf("3.0TD", "6.1TD", "6.2TD", "6.3TD", "6.4TD")
}
