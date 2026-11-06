package com.bm.backend.models

/**
 * Descriptive quotient reactive kVArh / active kWh (unit kVArh/kWh, unrounded) for one period, or overall when
 * [period] is null. [ratio] is null with a [reason] code when it cannot be computed truthfully.
 */
data class ReactiveRatio(
    val period: Int?,
    val reactiveKvarh: Double?,
    val activeKwh: Double?,
    val ratio: Double?,
    val reason: String?
)
