package com.bm.backend.utils

import com.bm.backend.models.ContractedPowerCheck
import com.bm.backend.models.StudySnapshotStatus
import com.bm.backend.testing.N8nFixtures
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class StudySnapshotMapperTest {
    private val cups = "ES0000000000000000AA0F"
    private val units = """"units": {"energy": "kWh", "power": "kW"}"""

    private fun mapFixture(name: String) = StudySnapshotMapper.map(N8nFixtures.response(name).data.snapshot, cups)

    /** Parses with the same lenient settings as the webhook decoding, so tokens like `15.1d` survive. */
    private fun mapRaw(snapshot: String, reference: String = cups) =
        StudySnapshotMapper.map(N8nFixtures.json.parseToJsonElement(snapshot), reference)

    private fun available(fields: String, extra: String = "") =
        """{"version": 1, "status": "available", $units, "fields": {"cups": "$cups"$fields}$extra}"""

    @Test
    fun `available v1 snapshot keeps values as published`() {
        val snapshot = mapFixture("snapshot_v1.json")
        assertEquals(StudySnapshotStatus.AVAILABLE, snapshot.status)
        assertNull(snapshot.reason)
        assertEquals(cups, snapshot.cups)
        assertEquals(ContractedPowerCheck.MATCH, snapshot.contractedPowerCheck)
        val f = assertNotNull(snapshot.fields)
        assertEquals(cups, f.cups)
        assertEquals(15.1, f.contractedPowerKw?.p1)
        assertEquals(20.0, f.contractedPowerKw?.p6)
        assertEquals(34.0, f.maxPowerBieKw)
        assertEquals(0.0, f.accessRightsKw)
        assertEquals(12000.0, f.annualConsumptionKwh)
        assertEquals("2025-09-30", f.lastReadingDate)
        assertEquals("2010-05-01", f.supplyStartDate)
        assertEquals(1000.0, f.annualConsumptionPeriodsKwh?.p1)
        assertNull(f.annualConsumptionPeriodsKwh?.p4)
        assertNull(f.postalCode)
        assertNull(f.maxPowerApmKw)
        assertNull(f.lastContractChangeDate)
        assertEquals("0031", f.distributorCode)
        assertEquals("3.0TD", f.tariff)
    }

    @Test
    fun `cups-only fields stay optional and suffix-only cups matches`() {
        val snapshot = mapFixture("snapshot_minimal.json")
        assertEquals(StudySnapshotStatus.AVAILABLE, snapshot.status)
        assertNull(snapshot.contractedPowerCheck)
        val f = assertNotNull(snapshot.fields)
        assertEquals(cups, f.cups)
        assertEquals(cups, snapshot.cups)
        listOf(f.distributorCode, f.distributorName, f.postalCode, f.province, f.municipality, f.tariff,
            f.supplyStartDate, f.lastContractChangeDate, f.lastSupplierChangeDate, f.lastReadingDate,
            f.annualConsumptionKwh, f.maxPowerBieKw, f.maxPowerApmKw, f.accessRightsKw, f.extensionRightsKw,
            f.annualConsumptionPeriodsKwh, f.contractedPowerKw).forEach { assertNull(it) }
    }

    @Test
    fun `full ISO datetime is retained verbatim`() {
        val s = mapRaw(available(""", "lastReadingDate": "2025-09-30T10:15:00""""))
        assertEquals("2025-09-30T10:15:00", s.fields?.lastReadingDate)
    }

    @Test
    fun `cups mismatch retains no values`() {
        val s = mapFixture("snapshot_cups_mismatch.json")
        assertEquals(StudySnapshotStatus.UNAVAILABLE, s.status)
        assertEquals("cups_mismatch", s.reason)
        assertNull(s.fields)
        assertNull(s.contractedPowerCheck)
        assertEquals(cups, s.cups)
        assertEquals("cups_mismatch", mapRaw(available(""), reference = " ").reason)
        assertEquals("cups_mismatch",
            mapRaw("""{"version": 1, "status": "unavailable", "reason": "cups_mismatch", $units, "fields": null, "checks": null}""").reason)
    }

    @Test
    fun `old and unavailable inputs`() {
        assertEquals("not_provided", mapFixture("annual_only.json").reason)
        assertEquals("not_provided", StudySnapshotMapper.map(null, cups).reason)
        assertEquals("not_captured", mapFixture("snapshot_unavailable.json").reason)
        listOf("not_captured", "parse_error", "cups_mismatch", "invalid_fields").forEach {
            val s = mapRaw("""{"version": 1, "status": "unavailable", "reason": "$it", $units, "fields": null, "checks": null}""")
            assertEquals(StudySnapshotStatus.UNAVAILABLE, s.status)
            assertEquals(it, s.reason)
            assertNull(s.fields)
        }
    }

    @Test
    fun `defects map to a single reason and nothing partial`() {
        val cases = mapOf(
            available(""", "maxPowerBieKw": "15.1"""") to "malformed",
            available(""", "maxPowerBieKw": 15.1d""") to "malformed",
            available(""", "maxPowerBieKw": null""") to "malformed",
            available(""", "contractedPowerKw": {"P7": 1}""") to "malformed",
            available(""", "contractedPowerKw": {"P1": "1"}""") to "malformed",
            available(""", "lastReadingDate": "2025-02-30"""") to "malformed",
            available(""", "lastReadingDate": "2025-09-30T25:00:00"""") to "malformed",
            available(""", "tariff": 3""") to "malformed",
            available("", """, "checks": {"contractedPowerVsPortal": "maybe"}""") to "malformed",
            """{"version": 2, "status": "available", $units, "fields": {"cups": "$cups"}}""" to "unsupported_version",
            """{"version": "1", "status": "available", $units, "fields": {"cups": "$cups"}}""" to "malformed",
            """{"version": 1d, "status": "available", $units, "fields": {"cups": "$cups"}}""" to "malformed",
            """{"version": 1, "status": "available", "units": {"energy": "kWh", "power": "W"}, "fields": {"cups": "$cups"}}""" to "malformed",
            """{"version": 1, "status": "available", $units, "fields": null}""" to "malformed",
            """{"version": 1, "status": "available", $units, "fields": {"cups": " "}}""" to "malformed",
            """{"version": 1, "status": "unavailable", "reason": "other", $units, "fields": null}""" to "malformed",
            """{"version": 1, "status": "maybe", $units}""" to "malformed",
            "\"x\"" to "malformed",
            "[]" to "malformed"
        )
        cases.forEach { (raw, reason) ->
            val s = mapRaw(raw)
            assertEquals(StudySnapshotStatus.UNAVAILABLE, s.status, raw)
            assertEquals(reason, s.reason, raw)
            assertNull(s.fields, raw)
            assertNull(s.contractedPowerCheck, raw)
        }
    }
}
