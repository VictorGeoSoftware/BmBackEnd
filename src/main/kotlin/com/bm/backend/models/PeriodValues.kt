package com.bm.backend.models

/** Values for tariff periods P1–P6 exactly as published upstream; `null` means not reported. */
data class PeriodValues(
    val p1: Double?,
    val p2: Double?,
    val p3: Double?,
    val p4: Double?,
    val p5: Double?,
    val p6: Double?
)
