package com.bm.backend.services.premiumreport

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.awt.Color

/** P1–P6 palette of the original study (feedback 2026-11-04) and readable header text. */
class PremiumReportThemeTest {
    @Test
    fun `period colours follow the original study and header text stays readable`() {
        assertEquals(
            listOf(0xFF0000, 0xFFC000, 0xFFFF00, 0xBDD7EE, 0x0070C0, 0x92D050),
            PremiumReportTheme.PERIOD_COLORS.map { it.rgb and 0xFFFFFF }
        )
        assertEquals(
            listOf(Color.WHITE, PremiumReportTheme.COLOR_TEXT, PremiumReportTheme.COLOR_TEXT, PremiumReportTheme.COLOR_TEXT, Color.WHITE, PremiumReportTheme.COLOR_TEXT),
            PremiumReportTheme.PERIOD_TEXT_COLORS
        )
    }
}
