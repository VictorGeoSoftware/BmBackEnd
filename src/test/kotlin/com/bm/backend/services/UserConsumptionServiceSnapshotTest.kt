package com.bm.backend.services

import com.bm.backend.models.ContractedPowerCheck
import com.bm.backend.models.FilteredPriceTableResponse
import com.bm.backend.models.StudySnapshotStatus
import com.bm.backend.testing.InMemoryUserConsumptionRepository
import com.bm.backend.testing.N8nFixtures
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UserConsumptionServiceSnapshotTest {
    private val cups = "ES0000000000000000AA0F"

    private fun run(fixture: String) = runBlocking {
        val externalApiService = mockk<ExternalApiService>()
        coEvery { externalApiService.processWithN8nWebhook(any()) } returns N8nFixtures.response(fixture)
        val priceTableService = mockk<PriceTableService>()
        every { priceTableService.getFilteredPriceTableResults(any()) } returns
            FilteredPriceTableResponse(success = true, results = emptyList(), iva = 0.21, impuestoElectrico = 0.0511)
        UserConsumptionService(InMemoryUserConsumptionRepository(), externalApiService, priceTableService)
            .processConsumptionReportFromCups(" $cups ")
    }

    @Test
    fun `matching snapshot is retained next to an unchanged history and report`() {
        val baseline = run("history_v1.json")
        val result = run("snapshot_v1.json")
        val s = result.snapshot
        assertEquals(StudySnapshotStatus.AVAILABLE, s.status)
        assertEquals(result.history.cups, s.cups)
        assertEquals(15.1, s.fields?.contractedPowerKw?.p1)
        assertEquals(34.0, s.fields?.maxPowerBieKw)
        assertEquals("2025-09-30", s.fields?.lastReadingDate)
        assertNull(s.fields?.annualConsumptionPeriodsKwh?.p4)
        assertNull(s.fields?.postalCode)
        assertEquals(ContractedPowerCheck.MATCH, s.contractedPowerCheck)
        assertEquals(baseline.report, result.report)
        assertEquals(baseline.history, result.history)
        assertEquals("not_provided", baseline.snapshot.reason)
    }

    @Test
    fun `snapshot defects never fail the job nor change report or history`() {
        val baseline = run("history_v1.json")
        mapOf(
            "snapshot_minimal.json" to null,
            "snapshot_cups_mismatch.json" to "cups_mismatch",
            "snapshot_unavailable.json" to "not_captured",
            "history_v1.json" to "not_provided"
        ).forEach { (fixture, reason) ->
            val result = run(fixture)
            assertEquals(baseline.report, result.report, fixture)
            assertEquals(baseline.history, result.history, fixture)
            assertEquals(reason, result.snapshot.reason, fixture)
        }
        val annual = run("annual_only.json")
        assertEquals("not_provided", annual.snapshot.reason)
        assertNull(run("snapshot_minimal.json").snapshot.fields?.lastReadingDate)
    }
}
