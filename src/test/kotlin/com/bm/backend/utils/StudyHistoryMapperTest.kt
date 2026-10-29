package com.bm.backend.utils

import com.bm.backend.models.N8nWebhookResponse
import com.bm.backend.models.StudyHistoryStatus
import com.bm.backend.testing.N8nFixtures
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StudyHistoryMapperTest {
    private val cups = "ES0000000000000000AA0F"

    private fun mapFixture(name: String) = StudyHistoryMapper.map(N8nFixtures.response(name).data.history, cups)

    private fun historyFrom(raw: String) =
        N8nFixtures.json.decodeFromString(N8nWebhookResponse.serializer(), raw).data.history

    @Test
    fun `available v1 history keeps values, nulls, zeros, dates and source order`() {
        val history = mapFixture("history_v1.json")
        assertEquals(StudyHistoryStatus.AVAILABLE, history.status)
        assertNull(history.reason)
        assertEquals(cups, history.cups)
        assertEquals(2, history.intervals.size)
        val first = history.intervals[0]
        assertEquals(LocalDate.of(2025, 1, 1), first.startDate)
        assertEquals(LocalDate.of(2025, 1, 15), first.endDate)
        assertEquals(16.103, first.maxDemand.p1)
        assertEquals(1112.0, first.active.p1)
        assertEquals(200.5, first.active.p2)
        assertEquals(0.0, first.active.p3)
        assertNull(first.active.p4)
        assertNull(first.maxDemand.p4)
        assertEquals(6.0, first.reactive.p6)
        val second = history.intervals[1]
        assertEquals(LocalDate.of(2025, 1, 16), second.startDate)
        assertEquals(0.25, second.active.p3)
        assertNull(second.reactive.p1)
    }

    @Test
    fun `missing history key is not_provided`() {
        val history = mapFixture("annual_only.json")
        assertEquals(StudyHistoryStatus.UNAVAILABLE, history.status)
        assertEquals("not_provided", history.reason)
        assertTrue(history.intervals.isEmpty())
        assertEquals("not_provided", StudyHistoryMapper.map(null, cups).reason)
    }

    @Test
    fun `upstream unavailable reasons pass through`() {
        assertEquals("missing", mapFixture("history_unavailable_missing.json").reason)
        val base = N8nFixtures.response("history_unavailable_missing.json").data.history!!.jsonObject
        for (reason in listOf("invalid_date", "invalid_measurement")) {
            val history = StudyHistoryMapper.map(JsonObject(base + ("reason" to JsonPrimitive(reason))), cups)
            assertEquals(StudyHistoryStatus.UNAVAILABLE, history.status)
            assertEquals(reason, history.reason)
        }
        val unknown = JsonObject(base + ("reason" to JsonPrimitive("other")))
        assertEquals("malformed", StudyHistoryMapper.map(unknown, cups).reason)
    }

    @Test
    fun `available with empty intervals is available and empty`() {
        val history = mapFixture("history_available_empty.json")
        assertEquals(StudyHistoryStatus.AVAILABLE, history.status)
        assertTrue(history.intervals.isEmpty())
    }

    @Test
    fun `defective histories are unavailable with a single reason and no partial data`() {
        val expected = mapOf(
            "history_string_number.json" to "malformed",
            "history_five_periods.json" to "malformed",
            "history_impossible_date.json" to "malformed",
            "history_not_object.json" to "malformed",
            "history_available_null_intervals.json" to "malformed",
            "history_version_2.json" to "unsupported_version"
        )
        expected.forEach { (fixture, reason) ->
            val history = mapFixture(fixture)
            assertEquals(StudyHistoryStatus.UNAVAILABLE, history.status, fixture)
            assertEquals(reason, history.reason, fixture)
            assertTrue(history.intervals.isEmpty(), fixture)
            assertFalse(history.reason!!.contains(cups), fixture)
        }
    }

    @Test
    fun `non-finite and non-numeric values are malformed`() {
        for (value in listOf("NaN", "Infinity", "true", "\"abc\"", "{}")) {
            val raw = N8nFixtures.text("history_v1.json").replace("\"P1\": 1112", "\"P1\": $value")
            assertEquals("malformed", StudyHistoryMapper.map(historyFrom(raw), cups).reason, value)
        }
    }

    @Test
    fun `reversed dates are retained as published`() {
        val raw = N8nFixtures.text("history_v1.json")
            .replace("\"endDate\": \"2025-01-15\"", "\"endDate\": \"2024-12-01\"")
        val history = StudyHistoryMapper.map(historyFrom(raw), cups)
        assertEquals(StudyHistoryStatus.AVAILABLE, history.status)
        assertEquals(LocalDate.of(2024, 12, 1), history.intervals[0].endDate)
    }
}
