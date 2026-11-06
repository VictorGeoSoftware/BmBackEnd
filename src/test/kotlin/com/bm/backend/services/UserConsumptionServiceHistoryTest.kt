package com.bm.backend.services

import com.bm.backend.models.FilteredPriceTableResponse
import com.bm.backend.models.StudyHistoryStatus
import com.bm.backend.testing.InMemoryUserConsumptionRepository
import com.bm.backend.testing.N8nFixtures
import com.bm.backend.utils.toCleanedData
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UserConsumptionServiceHistoryTest {
    private val cups = "ES0000000000000000AA0F"

    private fun serviceFor(fixture: String): UserConsumptionService {
        val externalApiService = mockk<ExternalApiService>()
        coEvery { externalApiService.processWithN8nWebhook(any()) } returns N8nFixtures.response(fixture)
        val priceTableService = mockk<PriceTableService>()
        every { priceTableService.getFilteredPriceTableResults(any()) } returns
            FilteredPriceTableResponse(success = true, results = emptyList(), iva = 0.21, impuestoElectrico = 0.0511)
        return UserConsumptionService(InMemoryUserConsumptionRepository(), externalApiService, priceTableService)
    }

    private fun run(fixture: String) = runBlocking { serviceFor(fixture).processConsumptionReportFromCups(" $cups ") }

    @Test
    fun `cups pipeline returns typed history with the cleaned cups`() {
        val result = run("history_v1.json")
        val history = result.history
        assertEquals(StudyHistoryStatus.AVAILABLE, history.status)
        assertEquals(cups, history.cups)
        assertEquals(result.report.consumptionData.cups, history.cups)
        assertEquals(2, history.intervals.size)
        assertEquals(16.103, history.intervals[0].maxDemand.p1)
        assertEquals(1112.0, history.intervals[0].active.p1)
        assertEquals(0.0, history.intervals[0].active.p3)
        assertNull(history.intervals[0].active.p4)
    }

    @Test
    fun `annual-only response keeps the annual result and reports not_provided`() {
        val result = run("annual_only.json")
        val n8n = N8nFixtures.response("annual_only.json")
        assertEquals(n8n.data.toCleanedData(n8n.processedAt), result.report.consumptionData)
        assertTrue(result.report.success)
        assertEquals(0.21, result.report.iva)
        assertEquals(StudyHistoryStatus.UNAVAILABLE, result.history.status)
        assertEquals("not_provided", result.history.reason)
    }

    @Test
    fun `history defects never change or fail the annual result`() {
        val baseline = run("annual_only.json").report
        val expected = mapOf(
            "history_v1.json" to null,
            "history_available_empty.json" to null,
            "history_unavailable_missing.json" to "missing",
            "history_string_number.json" to "malformed",
            "history_five_periods.json" to "malformed",
            "history_impossible_date.json" to "malformed",
            "history_not_object.json" to "malformed",
            "history_available_null_intervals.json" to "malformed",
            "history_version_2.json" to "unsupported_version"
        )
        expected.forEach { (fixture, reason) ->
            val result = run(fixture)
            assertEquals(baseline, result.report, fixture)
            assertEquals(reason, result.history.reason, fixture)
        }
    }
}
