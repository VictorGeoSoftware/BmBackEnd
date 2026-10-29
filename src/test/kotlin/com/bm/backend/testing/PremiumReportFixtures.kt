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
    // ---- Reactive-energy page fixtures (synthetic; window 31/07/2025 – 30/07/2026) ----

    val NULLS = PeriodValues(null, null, null, null, null, null)
    val REACTIVE_START: LocalDate = LocalDate.parse("2025-07-31")
    val ACTIVE_MONTH = PeriodValues(400.0, 500.0, 450.0, 600.0, 250.0, 2500.0)
    val REACTIVE_MONTH = PeriodValues(100.0, 120.0, 90.0, 150.0, 50.0, 400.0)

    fun reactiveRow(start: LocalDate, end: LocalDate, active: PeriodValues, reactive: PeriodValues) =
        StudyHistoryInterval(start, end, active, reactive, ones)

    /** 12 monthly rows ending 2026-07-31 (exclusive) with active and reactive values. */
    fun reactiveMonths(
        active: (Int) -> PeriodValues = { ACTIVE_MONTH },
        reactive: (Int) -> PeriodValues = { REACTIVE_MONTH }
    ) = (0 until 12).map { i ->
        reactiveRow(REACTIVE_START.plusMonths(i.toLong()), REACTIVE_START.plusMonths(i + 1L), active(i), reactive(i))
    }

    /** A: complete window plus an all-zero reactive row overlapping 2026-05-31..2026-06-30 (neutral, disclosed). */
    fun reactiveCompleteRows() = reactiveMonths() +
        reactiveRow(LocalDate.parse("2026-05-31"), LocalDate.parse("2026-06-30"), NULLS, ZEROS)

    /** B: every reactive cell recorded as 0.0, active > 0. */
    fun reactiveZeroRows() = reactiveMonths(reactive = { ZEROS })

    /** C: no reactive values at all. */
    fun reactiveMissingRows() = reactiveMonths(reactive = { NULLS })

    /** C2: reactive only before the window plus one reading crossing its start; active covers the window. */
    fun reactiveOutsideRows() =
        (0 until 11).map { i ->
            reactiveRow(LocalDate.parse("2024-06-30").plusMonths(i.toLong()), LocalDate.parse("2024-06-30").plusMonths(i + 1L), NULLS, REACTIVE_MONTH)
        } + reactiveRow(LocalDate.parse("2025-05-30"), LocalDate.parse("2025-08-31"), NULLS, REACTIVE_MONTH) +
            reactiveMonths(reactive = { NULLS })

    /** D: reactive only for the last 8 months (243 of 365 days). */
    fun reactiveIncompleteRows() = reactiveMonths(reactive = { i -> if (i < 4) NULLS else REACTIVE_MONTH })

    /** E: complete plus a non-zero reactive row overlapping 2026-01-15..2026-02-10 (unresolved). */
    fun reactiveOverlapRows() = reactiveMonths() +
        reactiveRow(LocalDate.parse("2026-01-15"), LocalDate.parse("2026-02-10"), NULLS, PeriodValues(5.0, 5.0, 5.0, 5.0, 5.0, 5.0))

    /** F: complete reactive, no active values (active_unavailable). */
    fun reactiveNoActiveRows() = reactiveMonths(active = { NULLS })

    /** F4: complete reactive; a non-zero active-only row overlaps (active_overlap). */
    fun reactiveActiveOverlapRows() = reactiveMonths() +
        reactiveRow(LocalDate.parse("2026-01-15"), LocalDate.parse("2026-02-10"), ACTIVE_MONTH, NULLS)

    /** F5: complete reactive; active only for the last 6 months (active_incomplete). */
    fun reactiveActiveIncompleteRows() = reactiveMonths(active = { i -> if (i < 6) NULLS else ACTIVE_MONTH })

    /** G: complete, P4–P6 reactive cells null in rows 2 and 5. */
    fun reactiveNullRows() = reactiveMonths(reactive = { i -> if (i == 2 || i == 5) REACTIVE_MONTH.copy(p4 = null, p5 = null, p6 = null) else REACTIVE_MONTH })

    /** L: 73 five-day rows covering the window exactly (chart cap 36). */
    fun reactiveLongRows() = (0 until 73).map { i ->
        val s = REACTIVE_START.plusDays(5L * i)
        reactiveRow(s, s.plusDays(5), PeriodValues(10.0, 12.0, 11.0, 15.0, 6.0, 40.0), PeriodValues(2.0 + i % 5, 3.0, 2.0, 4.0, 1.0, 9.0))
    }
    // ---- Demanded-power page fixtures (synthetic kW; window 31/07/2025 – 30/07/2026; contracted 15,1/15,1/20/20/34/34,5) ----

    val DEMAND_MONTH = PeriodValues(14.2, 15.0, 18.5, 19.25, 30.0, 33.0)

    fun demandRow(start: LocalDate, end: LocalDate, active: PeriodValues, demand: PeriodValues) =
        StudyHistoryInterval(start, end, active, ones, demand)

    /** 12 monthly rows ending 2026-07-31 (exclusive) with active energy and maximeter demand. */
    fun demandMonths(demand: (Int) -> PeriodValues = { DEMAND_MONTH }, active: (Int) -> PeriodValues = { ACTIVE_MONTH }) =
        (0 until 12).map { i ->
            demandRow(REACTIVE_START.plusMonths(i.toLong()), REACTIVE_START.plusMonths(i + 1L), active(i), demand(i))
        }

    /** A: complete window; P1 peak 16.103 kW in 31/12/2025 – 30/01/2026, P6 peak 34.75 kW in the last month. */
    fun demandCompleteRows() = demandMonths(demand = { i ->
        when (i) {
            5 -> DEMAND_MONTH.copy(p1 = 16.103)
            11 -> DEMAND_MONTH.copy(p6 = 34.75)
            else -> DEMAND_MONTH.copy(p1 = 14.0 + (i % 3) * 0.5)
        }
    })

    /** B: demand only for the last 8 months plus one reading crossing the window start. */
    fun demandIncompleteRows() = demandMonths(demand = { i -> if (i < 4) NULLS else DEMAND_MONTH }) +
        demandRow(LocalDate.parse("2025-06-30"), LocalDate.parse("2025-08-15"), NULLS, DEMAND_MONTH.copy(p1 = 17.0))

    /** C: complete plus a zero-active reading overlapping 2026-05-31..2026-06-30 whose P6 (40 kW) is the peak. */
    fun demandOverlapZeroActiveRows() = demandMonths() +
        demandRow(LocalDate.parse("2026-05-31"), LocalDate.parse("2026-06-30"), ZEROS, DEMAND_MONTH.copy(p6 = 40.0))

    /** D: demand only before the window plus one reading crossing its start; active covers the window. */
    fun demandOutsideRows() =
        (0 until 11).map { i ->
            demandRow(LocalDate.parse("2024-06-30").plusMonths(i.toLong()), LocalDate.parse("2024-06-30").plusMonths(i + 1L), ACTIVE_MONTH, DEMAND_MONTH)
        } + demandRow(LocalDate.parse("2025-05-30"), LocalDate.parse("2025-08-31"), NULLS, DEMAND_MONTH) +
            demandMonths(demand = { NULLS })

    /** G: every demand cell recorded as 0.0. */
    fun demandZeroRows() = demandMonths(demand = { ZEROS })

    /** H: P4–P6 never recorded; P1 null in rows 2 and 5. */
    fun demandInactiveRows() = demandMonths(demand = { i ->
        PeriodValues(if (i == 2 || i == 5) null else 14.2, 15.0, 18.5, null, null, null)
    })

    /** N1: active energy only, no maximeter values. */
    fun demandNoRows() = demandMonths(demand = { NULLS })

    /** L: 73 five-day rows covering the window exactly (chart cap 36). */
    fun demandLongRows() = (0 until 73).map { i ->
        val s = REACTIVE_START.plusDays(5L * i)
        demandRow(s, s.plusDays(5), ACTIVE_MONTH, DEMAND_MONTH.copy(p6 = 30.0 + i % 5))
    }

    /** F: contracted P2 recorded as 0, P3 missing. */
    fun demandSnapshotMissingZero(): StudySnapshot =
        available(fullFields().copy(contractedPowerKw = PeriodValues(15.1, 0.0, null, 20.0, 34.0, 34.5)))

    // ---- Assembled-document fixtures (report-document) ----

    /** Every family present: active, reactive and demand monthly for 12 months. */
    fun documentRows() = (0 until 12).map { i ->
        StudyHistoryInterval(REACTIVE_START.plusMonths(i.toLong()), REACTIVE_START.plusMonths(i + 1L), ACTIVE_MONTH, REACTIVE_MONTH, DEMAND_MONTH)
    }

    /** No reactive and no demand readings at all (N1 for both families ⇒ both sections omitted). */
    fun documentActiveOnlyRows() = (0 until 12).map { i ->
        StudyHistoryInterval(REACTIVE_START.plusMonths(i.toLong()), REACTIVE_START.plusMonths(i + 1L), ACTIVE_MONTH, NULLS, NULLS)
    }

    /** 130 ten-day intervals ending 2026-07-31 with all three families. */
    fun documentLongRows(): List<StudyHistoryInterval> {
        val end = LocalDate.parse("2026-07-31")
        return (0 until 130).map { i ->
            val s = end.minusDays(10L * (130 - i))
            StudyHistoryInterval(s, s.plusDays(10), PeriodValues(130.0 + i % 7, 160.0, 150.0, 200.0, 80.0, 820.0),
                PeriodValues(30.0 + i % 5, 40.0, 30.0, 50.0, 15.0, 130.0), DEMAND_MONTH.copy(p6 = 30.0 + i % 6))
        }
    }

    fun documentDataset(rows: List<StudyHistoryInterval> = documentRows(), offers: Int? = null): ReportDataset {
        val base = dataset().copy(history = history(rows))
        return if (offers == null) base
        else base.copy(comparison = ReportComparisonFixtures.pageScenarioOffers(offers).copy(supplyHolder = "Cliente Prueba S.L.", supplyAddress = "Calle Ficticia 1, 28001 Madrid"))
    }

    /** History wholly unavailable (not N1): reactive and demand keep their N2 disclosures. */
    fun documentUnavailableDataset(): ReportDataset =
        dataset().copy(history = StudyHistory(CUPS, StudyHistoryStatus.UNAVAILABLE, "calculation_error", emptyList()))

    // ---- Tariff-applicability fixtures (report-tariff-applicability; synthetic 2.0TD household) ----

    /**
     * 12 monthly 2.0TD rows (31/07/2025 – 30/07/2026): active/reactive P1–P3, maximeter P1–P2; out-of-tariff cells
     * are null, or recorded 0 when [zeros]. The last month's P2 maximeter (4.4 kW) is the peak.
     */
    fun tariff20Rows(zeros: Boolean = false): List<StudyHistoryInterval> {
        val x: Double? = if (zeros) 0.0 else null
        return (0 until 12).map { i ->
            StudyHistoryInterval(
                REACTIVE_START.plusMonths(i.toLong()), REACTIVE_START.plusMonths(i + 1L),
                PeriodValues(60.0 + i, 55.0, 130.0, x, x, x),
                PeriodValues(10.0, 8.0, 20.0, x, x, x),
                PeriodValues(3.9, if (i == 11) 4.4 else 4.1, x, x, x, x)
            )
        }
    }

    /** 2.0TD snapshot: contracted 4,6 kW in P1–P2 (P3–P6 null, or 0 when [zeros]), annual P1–P3. */
    fun tariff20Fields(zeros: Boolean = false, tariff: String? = "2.0TD"): StudySnapshotFields {
        val x: Double? = if (zeros) 0.0 else null
        return fullFields().copy(
            tariff = tariff,
            annualConsumptionKwh = 3000.0,
            annualConsumptionPeriodsKwh = PeriodValues(800.0, 700.0, 1500.0, x, x, x),
            contractedPowerKw = PeriodValues(4.6, 4.6, x, x, x, x)
        )
    }

    /**
     * Full-report dataset for a tariff scenario: [snapshotTariff] in the available snapshot, [tariffName] in the
     * comparison/legacy study (legacy P4–P6 coerced to 0 as today). The comparison request has 2 power / 3 energy rows.
     */
    fun tariffDataset(
        snapshotTariff: String? = "2.0TD",
        tariffName: String = "2.0TD",
        rows: List<StudyHistoryInterval> = tariff20Rows(),
        zeros: Boolean = false
    ): ReportDataset {
        val base = dataset(available(tariff20Fields(zeros, snapshotTariff)))
        val data = base.report.consumptionData.copy(
            feeType = tariffName,
            annualConsumption = 3000.0,
            annualConsumptionP1 = 800.0, annualConsumptionP2 = 700.0, annualConsumptionP3 = 1500.0,
            annualConsumptionP4 = 0.0, annualConsumptionP5 = 0.0, annualConsumptionP6 = 0.0
        )
        val comparison = base.comparison.copy(
            tariffName = tariffName,
            powerTermRows = listOf(
                com.bm.backend.models.ComparatorReportPeriodValue("P1", 4.6),
                com.bm.backend.models.ComparatorReportPeriodValue("P2", 4.6)
            ),
            energyConsumedRows = listOf(
                com.bm.backend.models.ComparatorReportPeriodIntValue("P1", 800),
                com.bm.backend.models.ComparatorReportPeriodIntValue("P2", 700),
                com.bm.backend.models.ComparatorReportPeriodIntValue("P3", 1500)
            )
        )
        return base.copy(history = history(rows), report = base.report.copy(consumptionData = data), comparison = comparison)
    }

    /** 2.0TD dataset whose legacy annual study publishes P5 = 120 kWh (history and snapshot P5 empty). */
    fun tariff20LegacyP5Dataset(): ReportDataset = tariffDataset().let { d ->
        d.copy(report = d.report.copy(consumptionData = d.report.consumptionData.copy(annualConsumptionP5 = 120.0, annualConsumption = 3120.0)))
    }

    /** 2.0TD rows with one active P5 = 120 kWh (out of tariff ⇒ inconsistent fallback). */
    fun tariff20InconsistentRows() = tariff20Rows().mapIndexed { i, r -> if (i == 6) r.copy(active = r.active.copy(p5 = 120.0)) else r }
}
