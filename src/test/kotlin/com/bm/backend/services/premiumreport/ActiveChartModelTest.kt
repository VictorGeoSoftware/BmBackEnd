package com.bm.backend.services.premiumreport

import com.bm.backend.models.ActiveWindowKind
import com.bm.backend.models.StudyHistory
import com.bm.backend.testing.PremiumReportFixtures
import com.bm.backend.utils.ActiveSummaryCalculator
import com.bm.backend.utils.HistoryCoverageCalculator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Geometry of the active-energy charts, computed from summary/history data only (synthetic fixtures). */
class ActiveChartModelTest {
    private fun summary(h: StudyHistory) =
        ActiveSummaryCalculator.calculate(h, HistoryCoverageCalculator.calculate(h), null, null)

    @Test
    fun `distribution bars equal calculator shares and labels carry kWh and percent`() {
        val s = summary(PremiumReportFixtures.history(PremiumReportFixtures.sampleRows()))
        val latest = s.windows.single { it.kind == ActiveWindowKind.LATEST_12M }
        val model = ActiveChartModel.distribution(latest)!!
        val shares = listOf(latest.shares!!.p1, latest.shares!!.p2, latest.shares!!.p3, latest.shares!!.p4, latest.shares!!.p5, latest.shares!!.p6)
        assertEquals(6, model.bars.size)
        model.bars.forEachIndexed { i, b ->
            assertEquals(i + 1, b.period)
            assertEquals(shares[i]!!, b.value!!, 1e-6)
            assertEquals(b.value!! / model.axisMax * model.plotWidth, b.width, 1e-6)
        }
        assertEquals("P1 7.201 kWh (9,9 %)", model.bars[0].label)
        assertTrue(model.axisMax >= shares.maxOf { it!! })
        assertEquals("0 %", model.valueTicks.first().label)
    }

    @Test
    fun `distribution is omitted unless the window is complete with shares`() {
        val s = summary(PremiumReportFixtures.history(PremiumReportFixtures.partialRows()))
        assertNull(ActiveChartModel.distribution(s.windows.first()))
    }

    @Test
    fun `axis maximum is a nice 1-2-5 ceiling`() {
        assertEquals(500.0, ActiveChartModel.niceCeiling(321.0), 1e-9)
        assertEquals(200.0, ActiveChartModel.niceCeiling(200.0), 1e-9)
        assertEquals(1.0, ActiveChartModel.niceCeiling(0.0), 1e-9)
        assertEquals(10.0, ActiveChartModel.niceCeiling(6.0), 1e-9)
    }
}
