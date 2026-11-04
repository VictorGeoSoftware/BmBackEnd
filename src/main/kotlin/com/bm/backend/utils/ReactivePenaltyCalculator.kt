package com.bm.backend.utils

import com.bm.backend.models.PeriodValues
import com.bm.backend.models.ReactivePenaltyReading
import com.bm.backend.models.ReactivePenaltySummary
import com.bm.backend.models.ReactivePenaltyTotal
import com.bm.backend.models.ReactivePenaltyWindowKind
import com.bm.backend.models.ReactiveSeriesPoint
import com.bm.backend.models.StudyHistory
import com.bm.backend.models.TariffPeriodProfile
import com.bm.backend.models.TariffProfileStatus
import java.time.LocalDate
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Estimated inductive reactive penalty per reading (spec report-reactive-penalty). For each penalised period
 * (P1–P5 of the tariff) of a row: cos φ = A / √(A² + R²), excess = max(0, R − 0,33·A), priced by
 * [ReactivePenaltyPrices]. Active and reactive always come from the same history row. Nothing is projected.
 */
object ReactivePenaltyCalculator {
    const val MISSING_VALUES = "missing_values"
    const val OVERLAP = "overlap"
    const val NOT_CALCULABLE = "not_calculable"
    const val NO_READINGS = "no_readings"

    fun calculate(history: StudyHistory, series: List<ReactiveSeriesPoint>, anchor: LocalDate?, profile: TariffPeriodProfile): ReactivePenaltySummary {
        if (!applies(profile) || anchor == null) return ReactivePenaltySummary.NOT_APPLICABLE
        val periods = ReactivePenaltyPrices.PERIODS.filter { it in profile.energyPeriods }
        val readings = series.map { p ->
            val row = history.intervals[p.index]
            val penalty = rowPenalty(row.active, row.reactive, periods)
            ReactivePenaltyReading(p.index, p.start, p.endExclusive, penalty, if (penalty == null) MISSING_VALUES else null, p.inUnresolvedOverlap)
        }
        val totals = listOf(
            ReactivePenaltyWindowKind.LAST_12M to (anchor.minusMonths(12) to anchor),
            ReactivePenaltyWindowKind.PREVIOUS_12M to (anchor.minusMonths(24) to anchor.minusMonths(12)),
            ReactivePenaltyWindowKind.LAST_24M to (anchor.minusMonths(24) to anchor)
        ).map { (kind, range) -> total(kind, range.first, range.second, readings, history, periods) }
        return ReactivePenaltySummary(true, readings, totals)
    }

    fun applies(profile: TariffPeriodProfile): Boolean =
        profile.status == TariffProfileStatus.SUPPORTED && profile.tariffCode in ReactivePenaltyPrices.TARIFFS

    /** Penalty (€) of one row over [periods]; null when a penalised period lacks its active or reactive value. */
    fun rowPenalty(active: PeriodValues, reactive: PeriodValues, periods: List<Int>): Double? {
        val a = cells(active)
        val r = cells(reactive)
        var sum = 0.0
        for (p in periods) {
            val av = a[p - 1]
            val rv = r[p - 1]
            if (av == null || rv == null) return null
            sum += periodPenalty(av, rv)
        }
        return sum
    }

    fun periodPenalty(active: Double, reactive: Double): Double {
        if (active == 0.0 && reactive == 0.0) return 0.0
        val cosPhi = active / sqrt(active * active + reactive * reactive)
        val price = when {
            cosPhi >= ReactivePenaltyPrices.COS_PHI_FREE -> return 0.0
            cosPhi >= ReactivePenaltyPrices.COS_PHI_HIGH -> ReactivePenaltyPrices.PRICE_MEDIUM
            else -> ReactivePenaltyPrices.PRICE_HIGH
        }
        return max(0.0, reactive - ReactivePenaltyPrices.FREE_RATIO * active) * price
    }

    private fun total(
        kind: ReactivePenaltyWindowKind, start: LocalDate, end: LocalDate,
        readings: List<ReactivePenaltyReading>, history: StudyHistory, periods: List<Int>
    ): ReactivePenaltyTotal {
        val inside = readings.filter { it.start >= start && it.endExclusive <= end }
        val reason = when {
            inside.isEmpty() -> NO_READINGS
            inside.any { it.inUnresolvedOverlap } -> OVERLAP
            inside.any { it.penaltyEur == null } -> NOT_CALCULABLE
            else -> null
        }
        if (reason != null) return ReactivePenaltyTotal(kind, start, end, inside.size, null, null, null, reason)
        var a = 0.0
        var r = 0.0
        inside.forEach { reading ->
            val row = history.intervals[reading.index]
            periods.forEach { p -> a += cells(row.active)[p - 1]!!; r += cells(row.reactive)[p - 1]!! }
        }
        val cosPhi = if (a == 0.0 && r == 0.0) null else a / sqrt(a * a + r * r)
        return ReactivePenaltyTotal(kind, start, end, inside.size, inside.sumOf { it.penaltyEur!! }, r, cosPhi, null)
    }

    private fun cells(v: PeriodValues) = listOf(v.p1, v.p2, v.p3, v.p4, v.p5, v.p6)
}
