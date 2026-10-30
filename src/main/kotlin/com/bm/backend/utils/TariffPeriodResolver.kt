package com.bm.backend.utils

import com.bm.backend.models.CleanedConsumptionData
import com.bm.backend.models.PeriodValues
import com.bm.backend.models.ReportDataset
import com.bm.backend.models.StudyHistory
import com.bm.backend.models.StudySnapshotFields
import com.bm.backend.models.StudySnapshotStatus
import com.bm.backend.models.TariffPeriodProfile
import com.bm.backend.models.TariffProfileStatus

/**
 * Pure, never-throwing decision of which tariff periods the Premium report shows (spec report-tariff-applicability).
 * [SUPPORTED] is the only place tariff periods are defined. Candidates: the available snapshot's `tariff`, then the
 * comparison's `tariffName`; texts that do not look like an access-tariff code (blank, `fixed`, …) carry no evidence.
 * A supported tariff with non-zero source values outside its periods (history, snapshot or legacy annual) falls back
 * (data is never hidden).
 * Unknown/conflict fallback periods are those with any non-null history/snapshot value (none ⇒ P1–P6); the
 * inconsistent fallback shows the tariff's periods plus every period with a non-zero value in any source, legacy included.
 */
object TariffPeriodResolver {
    /** Supported tariffs: code → (energy periods, power periods). */
    val SUPPORTED: Map<String, Pair<List<Int>, List<Int>>> = mapOf(
        "2.0TD" to ((1..3).toList() to (1..2).toList()),
        "3.0TD" to ((1..6).toList() to (1..6).toList())
    )

    private val CODE = Regex("^(\\d)[.,_]?(\\d)\\s*TD\\b")

    /** `3.0TD ML` → `3.0TD`, `2,0td` → `2.0TD`, `6.1TD` → `6.1TD`; null when the text is not a tariff code. */
    fun normalise(text: String?): String? {
        val t = text?.trim()?.uppercase() ?: return null
        val m = CODE.find(t) ?: return null
        return "${m.groupValues[1]}.${m.groupValues[2]}TD"
    }

    fun resolve(dataset: ReportDataset): TariffPeriodProfile = try {
        val fields = dataset.snapshot.fields?.takeIf { dataset.snapshot.status == StudySnapshotStatus.AVAILABLE }
        resolve(fields, dataset.comparison.tariffName, dataset.history, dataset.report.consumptionData)
    } catch (e: Exception) {
        TariffPeriodProfile(null, TariffPeriodProfile.ALL_PERIODS, TariffPeriodProfile.ALL_PERIODS, TariffProfileStatus.FALLBACK_UNKNOWN)
    }

    /** [fields] must be the AVAILABLE snapshot's fields (null otherwise). */
    fun resolve(
        fields: StudySnapshotFields?,
        tariffName: String?,
        history: StudyHistory,
        legacy: CleanedConsumptionData? = null
    ): TariffPeriodProfile {
        val snapshotText = fields?.tariff?.trim()?.takeIf { it.isNotEmpty() }
        val nameText = tariffName?.trim()?.takeIf { it.isNotEmpty() }
        val a = normalise(snapshotText)
        val b = normalise(nameText)
        fun fallback(status: TariffProfileStatus, code: String?) = TariffPeriodProfile(
            code, presentEnergy(fields, history), presentPower(fields, history), status, snapshotText, nameText
        )
        if (a != null && b != null && a != b) return fallback(TariffProfileStatus.FALLBACK_CONFLICT, null)
        val code = a ?: b
        val periods = code?.let { SUPPORTED[it] } ?: return fallback(TariffProfileStatus.FALLBACK_UNKNOWN, code)
        val (energy, power) = periods
        if (outside(fields, history, energy, power) || legacyOutside(legacy, energy)) {
            val shownEnergy = union(energy, history.intervals.flatMap { listOf(it.active, it.reactive) } +
                listOfNotNull(fields?.annualConsumptionPeriodsKwh, legacy?.let(::legacyValues)))
            val shownPower = union(power, history.intervals.map { it.maxDemand } + listOfNotNull(fields?.contractedPowerKw))
            return TariffPeriodProfile(code, shownEnergy, shownPower, TariffProfileStatus.FALLBACK_INCONSISTENT, snapshotText, nameText)
        }
        return TariffPeriodProfile(code, energy, power, TariffProfileStatus.SUPPORTED, snapshotText, nameText)
    }

    private fun cells(v: PeriodValues) = listOf(v.p1, v.p2, v.p3, v.p4, v.p5, v.p6)

    private fun nonZeroOutside(v: PeriodValues?, periods: List<Int>): Boolean =
        v != null && cells(v).withIndex().any { (i, c) -> (i + 1) !in periods && c != null && c != 0.0 }

    private fun outside(fields: StudySnapshotFields?, history: StudyHistory, energy: List<Int>, power: List<Int>): Boolean =
        history.intervals.any { nonZeroOutside(it.active, energy) || nonZeroOutside(it.reactive, energy) || nonZeroOutside(it.maxDemand, power) } ||
            nonZeroOutside(fields?.annualConsumptionPeriodsKwh, energy) ||
            nonZeroOutside(fields?.contractedPowerKw, power)

    /** Legacy annual P1–P6 (blanks already 0): only a non-zero value outside the energy periods counts; never used for fallback periods. */
    private fun legacyValues(l: CleanedConsumptionData) = PeriodValues(l.annualConsumptionP1, l.annualConsumptionP2,
        l.annualConsumptionP3, l.annualConsumptionP4, l.annualConsumptionP5, l.annualConsumptionP6)

    private fun legacyOutside(legacy: CleanedConsumptionData?, energy: List<Int>): Boolean =
        legacy != null && nonZeroOutside(legacyValues(legacy), energy)

    /** FALLBACK_INCONSISTENT periods: the tariff's periods ∪ every period with a non-zero value in [values] (nothing hidden). */
    private fun union(tariff: List<Int>, values: List<PeriodValues>): List<Int> =
        (1..6).filter { p -> p in tariff || values.any { cells(it)[p - 1].let { c -> c != null && c != 0.0 } } }

    private fun present(values: List<PeriodValues>): List<Int> =
        (1..6).filter { p -> values.any { cells(it)[p - 1] != null } }.ifEmpty { TariffPeriodProfile.ALL_PERIODS }

    private fun presentEnergy(fields: StudySnapshotFields?, history: StudyHistory): List<Int> =
        present(history.intervals.flatMap { listOf(it.active, it.reactive) } + listOfNotNull(fields?.annualConsumptionPeriodsKwh))

    private fun presentPower(fields: StudySnapshotFields?, history: StudyHistory): List<Int> =
        present(history.intervals.map { it.maxDemand } + listOfNotNull(fields?.contractedPowerKw))
}
