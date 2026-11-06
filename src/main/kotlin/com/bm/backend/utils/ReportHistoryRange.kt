package com.bm.backend.utils

import com.bm.backend.models.StudyHistory
import java.time.LocalDate

/**
 * Period shown by the Premium readings tables and per-reading charts (user decision 2026-11-04: last 24 months, like
 * the original study): readings fully contained in [anchor − 24 months, anchor), the same containment rule as the
 * 12-month windows. The 12-month figures are not affected.
 */
object ReportHistoryRange {
    const val MONTHS = 24L

    fun start(anchor: LocalDate): LocalDate = anchor.minusMonths(MONTHS)

    /** Source-order indices of the history rows contained in the range; empty without an anchor. */
    fun indices(history: StudyHistory, anchor: LocalDate?): List<Int> {
        if (anchor == null) return emptyList()
        val start = start(anchor)
        return history.intervals.indices.filter { history.intervals[it].startDate >= start && history.intervals[it].endDate <= anchor }
    }
}
