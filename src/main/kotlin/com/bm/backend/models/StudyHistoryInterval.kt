package com.bm.backend.models

import java.time.LocalDate

/** One dated source interval of the study history (source order, not interpreted as a month). */
data class StudyHistoryInterval(
    val startDate: LocalDate,
    val endDate: LocalDate,
    val active: PeriodValues,
    val reactive: PeriodValues,
    val maxDemand: PeriodValues
)
