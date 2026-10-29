package com.bm.backend.testing

import com.bm.backend.models.ContractedPowerCheck
import com.bm.backend.models.PeriodValues
import com.bm.backend.models.ReportDataset
import com.bm.backend.models.StudySnapshot
import com.bm.backend.models.StudySnapshotFields
import com.bm.backend.models.StudySnapshotStatus
import com.bm.backend.models.StudyHistory
import com.bm.backend.models.StudyHistoryInterval
import com.bm.backend.models.StudyHistoryStatus
import java.time.LocalDate
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/** Synthetic datasets for Premium report rendering tests (no customer data). */
object PremiumReportFixtures {
    const val CUPS = "ES0000000000000000AA0F"
    const val JOB_ID = "job-synthetic-0001"

    /** 2026-10-29 10:00 Europe/Madrid. */
    val CLOCK: Clock = Clock.fixed(Instant.parse("2026-10-29T09:00:00Z"), ZoneId.of("UTC"))

    private val study by lazy { ReportComparisonFixtures.study(CUPS) }

    fun fullFields(): StudySnapshotFields = StudySnapshotFields(
        cups = CUPS,
        distributorCode = "0021",
        distributorName = "Distribuidora Ficticia S.A.",
        postalCode = "28001",
        province = "Madrid",
        municipality = "Madrid",
        tariff = "3.0TD",
        supplyStartDate = "2015-03-01",
        lastContractChangeDate = "2020-06-01",
        lastSupplierChangeDate = "2022-01-15",
        lastReadingDate = "2026-07-31",
        annualConsumptionKwh = 54321.0,
        maxPowerBieKw = 77.7,
        maxPowerApmKw = 88.8,
        accessRightsKw = 66.6,
        extensionRightsKw = 55.5,
        contractedPowerKw = PeriodValues(15.1, 15.1, 20.0, 20.0, 34.0, 34.5)
    )

    fun available(fields: StudySnapshotFields): StudySnapshot =
        StudySnapshot(CUPS, StudySnapshotStatus.AVAILABLE, null, fields, ContractedPowerCheck.MISMATCH)

    fun dataset(
        snapshot: StudySnapshot = available(fullFields()),
        holder: String = "Cliente Prueba S.L.",
        address: String = "Calle Ficticia 1, 28001 Madrid"
    ): ReportDataset {
        val comparison = ReportComparisonFixtures.comparison(study.report, CUPS)
            .copy(supplyHolder = holder, supplyAddress = address)
        return ReportDataset(JOB_ID, CUPS, study.report, study.history, snapshot, comparison)
    }

    // ---- Active-energy page fixtures (synthetic; same shape as the active-summary scenario A) ----

    private val ones = PeriodValues(1.0, 1.0, 1.0, 1.0, 1.0, 1.0)
    val ZEROS = PeriodValues(0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
    val LATEST_TARGETS = listOf(7201, 8225, 7467, 9031, 3684, 37069)
    val PREVIOUS_TARGETS = listOf(6500, 7400, 6800, 8100, 3300, 32848)
    val LATEST_PERIODS = PeriodValues(7201.0, 8225.0, 7467.0, 9031.0, 3684.0, 37069.0)

    fun pv(vararg v: Double?) = PeriodValues(v[0], v[1], v[2], v[3], v[4], v[5])

    fun row(start: LocalDate, end: LocalDate, active: PeriodValues) = StudyHistoryInterval(start, end, active, ones, ones)

    fun monthly(base: LocalDate, from: Int, to: Int, active: (Int) -> PeriodValues) =
        (from until to).map { i -> row(base.plusMonths(i.toLong()), base.plusMonths(i + 1L), active(i - from)) }

    /** Integer split of each period target over rows in proportion to their days (exact totals, realistic kWh/day). */
    private fun byDays(targets: List<Int>, bounds: List<LocalDate>): List<PeriodValues> {
        val days = (0 until bounds.size - 1).map { java.time.temporal.ChronoUnit.DAYS.between(bounds[it], bounds[it + 1]) }
        val total = days.sum().toDouble()
        val perPeriod = targets.map { t ->
            val parts = days.map { Math.round(t * it / total).toInt() }.toMutableList()
            parts[parts.lastIndex] = t - parts.dropLast(1).sum()
            parts
        }
        return days.indices.map { i -> pv(*perPeriod.map { it[i].toDouble() }.toTypedArray()) }
    }

    /** 2023-08-31 → 2026-07-31, one month split at 2026-03-15, zero row overlapping 2026-05-31..2026-06-30. */
    fun sampleRows(): List<StudyHistoryInterval> {
        val base = LocalDate.parse("2023-08-31")
        val early = monthly(base, 0, 11) { pv(100.0, 100.0, 100.0, 100.0, 100.0, 100.0) }
        val previousBounds = (11..23).map { base.plusMonths(it.toLong()) }
        val previousValues = byDays(PREVIOUS_TARGETS, previousBounds)
        val previous = (0 until 12).map { row(previousBounds[it], previousBounds[it + 1], previousValues[it]) }
        val latestDates = (23..33).map { base.plusMonths(it.toLong()) }.toMutableList()
        latestDates.add(latestDates.indexOf(LocalDate.parse("2026-03-31")), LocalDate.parse("2026-03-15"))
        val bounds = latestDates + LocalDate.parse("2026-07-31")
        val latestValues = byDays(LATEST_TARGETS, bounds)
        val latest = (0 until bounds.size - 1).map { row(bounds[it], bounds[it + 1], latestValues[it]) }
        val zero = row(LocalDate.parse("2026-05-31"), LocalDate.parse("2026-06-30"), ZEROS)
        return early + previous + latest + zero
    }

    /** 8 monthly rows ending 2026-07-31. */
    fun partialRows(): List<StudyHistoryInterval> =
        monthly(LocalDate.parse("2025-11-30"), 0, 8) { pv(500.0, 600.0, 700.0, 800.0, 300.0, 2000.0) }

    /** Sample plus a non-zero row overlapping the latest window (blocks a firm latest figure). */
    fun overlapRows(): List<StudyHistoryInterval> =
        sampleRows() + row(LocalDate.parse("2026-01-15"), LocalDate.parse("2026-02-10"), pv(50.0, 50.0, 50.0, 50.0, 50.0, 50.0))

    /** Sample plus a non-zero row overlapping the previous window only (latest stays firm). */
    fun previousOverlapRows(): List<StudyHistoryInterval> =
        sampleRows() + row(LocalDate.parse("2025-01-15"), LocalDate.parse("2025-02-10"), pv(50.0, 50.0, 50.0, 50.0, 50.0, 50.0))

    /** 40 monthly rows ending 2026-07-31. */
    fun longRows(): List<StudyHistoryInterval> =
        monthly(LocalDate.parse("2023-03-31"), 0, 40) { i -> pv(400.0 + i * 7, 500.0, 450.0, 600.0, 250.0, 2500.0 + (i % 12) * 90) }

    fun history(rows: List<StudyHistoryInterval>) = StudyHistory(CUPS, StudyHistoryStatus.AVAILABLE, null, rows)

    /** SIPS P6 differs by 100 kWh from the sample's latest window; the Basic-study P1 differs by 201. */
    fun activeSnapshot(): StudySnapshot = available(
        fullFields().copy(annualConsumptionKwh = 72777.0, annualConsumptionPeriodsKwh = LATEST_PERIODS.copy(p6 = 37169.0))
    )

    fun activeDataset(history: StudyHistory, snapshot: StudySnapshot = activeSnapshot()): ReportDataset {
        val base = dataset(snapshot)
        val legacy = LATEST_TARGETS.map { it.toDouble() }.toMutableList().also { it[0] = 7000.0 }
        val data = base.report.consumptionData.copy(
            annualConsumption = legacy.sum(),
            annualConsumptionP1 = legacy[0], annualConsumptionP2 = legacy[1], annualConsumptionP3 = legacy[2],
            annualConsumptionP4 = legacy[3], annualConsumptionP5 = legacy[4], annualConsumptionP6 = legacy[5]
        )
        return base.copy(history = history, report = base.report.copy(consumptionData = data))
    }
}
