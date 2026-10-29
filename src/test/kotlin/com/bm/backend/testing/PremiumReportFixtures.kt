package com.bm.backend.testing

import com.bm.backend.models.ContractedPowerCheck
import com.bm.backend.models.PeriodValues
import com.bm.backend.models.ReportDataset
import com.bm.backend.models.StudySnapshot
import com.bm.backend.models.StudySnapshotFields
import com.bm.backend.models.StudySnapshotStatus
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
}
