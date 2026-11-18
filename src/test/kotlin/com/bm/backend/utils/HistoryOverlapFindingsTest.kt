package com.bm.backend.utils

import com.bm.backend.models.HistoryOverlapEffect
import com.bm.backend.models.MeasurementFamily
import com.bm.backend.models.PeriodValues
import com.bm.backend.models.StudyHistory
import com.bm.backend.models.StudyHistoryInterval
import com.bm.backend.models.StudyHistoryStatus
import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HistoryOverlapFindingsTest {
    private val none = PeriodValues(null, null, null, null, null, null)
    private val zeros = PeriodValues(0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
    private fun v(x: Double) = PeriodValues(x, x, x, x, x, x)

    private fun row(start: String, end: String, active: PeriodValues = none, reactive: PeriodValues = none, demand: PeriodValues = none) =
        StudyHistoryInterval(LocalDate.parse(start), LocalDate.parse(end), active, reactive, demand)

    private fun history(vararg rows: StudyHistoryInterval) =
        StudyHistory("ES0000000000000000AA0F", StudyHistoryStatus.AVAILABLE, null, rows.toList())

    private fun findings(h: StudyHistory) = HistoryOverlapFindings.from(h, HistoryCoverageCalculator.calculate(h))

    @Test
    fun `contiguous rows produce no findings`() {
        assertTrue(findings(history(row("2025-01-01", "2025-02-01", v(1.0)), row("2025-02-01", "2025-03-01", v(2.0)))).isEmpty())
    }

    @Test
    fun `an active overlap without a zero row blocks the total, with 1-based rows and dates`() {
        val f = findings(history(row("2025-01-01", "2025-03-01", v(1.0)), row("2025-01-15", "2025-02-15", v(2.0)))).single()
        assertEquals(MeasurementFamily.ACTIVE, f.family)
        assertEquals(1 to 2, f.firstRow to f.secondRow)
        assertEquals(31, f.overlapDays)
        assertEquals(null, f.zeroRow)
        assertEquals(HistoryOverlapEffect.BLOCKS_TOTAL, f.effect)
        assertEquals(
            "ACTIVE rows 1 [2025-01-01..2025-03-01) x 2 [2025-01-15..2025-02-15): 31d, zero row none -> blocks_total",
            HistoryOverlapFindings.describe(f)
        )
    }

    @Test
    fun `an all-zero row makes the pair neutral, per family, and blanks never qualify`() {
        val h = history(
            row("2025-01-01", "2025-03-01", active = v(1.0), reactive = v(1.0)),
            row("2025-01-15", "2025-02-15", active = zeros, reactive = PeriodValues(0.0, 0.0, 0.0, 0.0, 0.0, null))
        )
        val byFamily = findings(h).associateBy { it.family }
        assertEquals(HistoryOverlapEffect.NEUTRAL_ZERO_ROW, byFamily.getValue(MeasurementFamily.ACTIVE).effect)
        assertEquals(2, byFamily.getValue(MeasurementFamily.ACTIVE).zeroRow)
        assertEquals(HistoryOverlapEffect.BLOCKS_TOTAL, byFamily.getValue(MeasurementFamily.REACTIVE).effect)
    }

    @Test
    fun `demand overlaps only hide the mean`() {
        val f = findings(history(row("2025-01-01", "2025-03-01", demand = v(10.0)), row("2025-01-15", "2025-02-15", demand = v(12.0)))).single()
        assertEquals(MeasurementFamily.MAX_DEMAND, f.family)
        assertEquals(HistoryOverlapEffect.HIDES_DEMAND_MEAN, f.effect)
    }

    @Test
    fun `unavailable history yields no findings`() {
        val h = StudyHistory.unavailable("ES0000000000000000AA0F", "not_provided")
        assertTrue(findings(h).isEmpty())
    }
}
