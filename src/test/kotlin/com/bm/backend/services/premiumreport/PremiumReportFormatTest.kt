package com.bm.backend.services.premiumreport

import com.bm.backend.models.PeriodValues
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

class PremiumReportFormatTest {
    @Test
    fun `kW values use Spanish format with published precision up to three decimals`() {
        assertEquals("15,1", PremiumReportFormat.number(15.1))
        assertEquals("34", PremiumReportFormat.number(34.0))
        assertEquals("1.234,567", PremiumReportFormat.number(1234.567))
        assertEquals("0,124", PremiumReportFormat.number(0.12449))
    }

    @Test
    fun `period powers list only reported periods`() {
        assertEquals(
            "P1:\u00A015,1\u00A0kW · P3:\u00A020\u00A0kW",
            PremiumReportFormat.periodPowers(PeriodValues(15.1, null, 20.0, null, null, null))
        )
        assertNull(PremiumReportFormat.periodPowers(PeriodValues(null, null, null, null, null, null)))
    }

    @Test
    fun `dates are shown as dd-MM-yyyy`() {
        assertEquals("31/07/2026", PremiumReportFormat.isoDate("2026-07-31"))
        assertEquals("31/07/2026", PremiumReportFormat.isoDate("2026-07-31T23:10:00"))
        // 2026-10-28T23:30Z is already 29/10 in Madrid.
        val clock = Clock.fixed(Instant.parse("2026-10-28T23:30:00Z"), ZoneId.of("UTC"))
        assertEquals("29/10/2026", PremiumReportFormat.issueDate(clock))
    }

    @Test
    fun `decimal2 rounds half up with two decimals and Spanish grouping`() {
        assertEquals("0,25", PremiumReportFormat.decimal2(0.25))
        assertEquals("0,19", PremiumReportFormat.decimal2(10920.0 / 56400.0))
        assertEquals("0,13", PremiumReportFormat.decimal2(0.125))
        assertEquals("0,00", PremiumReportFormat.decimal2(0.0))
        assertEquals("0,00", PremiumReportFormat.decimal2(-0.001))
        assertEquals("1.234,57", PremiumReportFormat.decimal2(1234.567))
    }
}
