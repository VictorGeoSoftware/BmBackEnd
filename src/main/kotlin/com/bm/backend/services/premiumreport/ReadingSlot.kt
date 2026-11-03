package com.bm.backend.services.premiumreport

import com.bm.backend.models.PeriodValues
import java.time.LocalDate

/** One source reading as plotted by [StackedReadingsChart]: its dates, published cells and overlap flag (not a month). */
data class ReadingSlot(
    val start: LocalDate,
    val endExclusive: LocalDate,
    val values: PeriodValues,
    val overlap: Boolean
)
