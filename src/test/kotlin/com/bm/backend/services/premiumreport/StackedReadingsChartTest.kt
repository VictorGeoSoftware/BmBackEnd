package com.bm.backend.services.premiumreport

import com.bm.backend.testing.PremiumReportFixtures.pv
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate

/** Per-reading stacked chart (feedback 2026-11-03, option a): one slot per reading, segments = published cells. */
class StackedReadingsChartTest {
    private val d = LocalDate.parse("2025-01-01")
    private fun slot(i: Long, v: com.bm.backend.models.PeriodValues, overlap: Boolean = false) =
        ReadingSlot(d.plusDays(30 * i), d.plusDays(30 * (i + 1)), v, overlap)

    private val all = (1..6).toList()

    @Test
    fun `one slot per reading in table order, segments equal the cells and stack to the total`() {
        val slots = listOf(
            slot(0, pv(100.0, 200.0, null, 0.0, 50.0, 25.0)),
            slot(1, pv(10.0, 20.0, 30.0, 40.0, 50.0, 60.0))
        )
        val m = StackedReadingsChart.model(slots, all, "kWh")
        assertEquals(2, m.totalRows)
        assertEquals(all, m.legend)
        assertEquals("kWh", m.unit)
        val first = m.bars.filter { it.sourceIndex == 0 }
        // Null and zero cells draw no segment; the rest keep their period colour and value.
        assertEquals(listOf(1, 2, 5, 6), first.map { it.period })
        assertEquals(listOf(100.0, 200.0, 50.0, 25.0), first.map { it.value })
        assertTrue(first.all { it.style == ChartBarStyle.FILLED })
        first.zipWithNext().forEach { (a, b) -> assertEquals(a.y + a.height, b.y, 1e-9) }
        val top = first.last().let { it.y + it.height } - m.plotBottom
        assertEquals(375.0 / m.axisMax * m.plotHeight, top, 1e-6)
        assertEquals(375.0, StackedReadingsChart.total(slots[0], all)!!, 1e-9)
        // Second slot sits to the right of the first, same width.
        val x0 = first.first().x
        val x1 = m.bars.first { it.sourceIndex == 1 }.x
        assertTrue(x1 > x0)
        assertEquals(m.plotWidth / 2, x1 - x0, 1e-6)
    }

    @Test
    fun `tariff periods restrict segments and totals (2_0TD)`() {
        val s = slot(0, pv(1.0, 2.0, 3.0, 99.0, 99.0, 99.0))
        val m = StackedReadingsChart.model(listOf(s), listOf(1, 2, 3), "kWh")
        assertEquals(listOf(1, 2, 3), m.bars.map { it.period })
        assertEquals(listOf(1, 2, 3), m.legend)
        assertEquals(6.0, StackedReadingsChart.total(s, listOf(1, 2, 3))!!, 1e-9)
    }

    @Test
    fun `null, zero and overlapping readings use the disclosed styles`() {
        val m = StackedReadingsChart.model(
            listOf(
                slot(0, pv(null, null, null, null, null, null)),
                slot(1, pv(0.0, 0.0, 0.0, 0.0, 0.0, 0.0)),
                slot(2, pv(5.0, 5.0, 0.0, 0.0, 0.0, 0.0), overlap = true)
            ), all, "kVArh"
        )
        assertEquals(ChartBarStyle.NO_DATA, m.bars.single { it.sourceIndex == 0 }.style)
        assertNull(m.bars.single { it.sourceIndex == 0 }.value)
        assertEquals(ChartBarStyle.ZERO_MARKER, m.bars.single { it.sourceIndex == 1 }.style)
        val o = m.bars.single { it.sourceIndex == 2 }
        assertEquals(ChartBarStyle.OUTLINED, o.style)
        assertEquals(10.0, o.value!!, 1e-9)
    }

    @Test
    fun `many readings stay in one chart with one rotated label per bar`() {
        val slots = (0L until 36L).map { slot(it, pv(1.0, 0.0, 0.0, 0.0, 0.0, 0.0)) }
        val m = StackedReadingsChart.model(slots, all, "kWh")
        assertEquals(36, m.totalRows)
        assertEquals(36, m.bars.map { it.sourceIndex }.distinct().size)
        assertEquals(36, m.categoryTicks.size)
        assertTrue(m.categoryTicks.all { it.rotated })
        val dense = StackedReadingsChart.model((0L until 72L).map { slot(it, pv(1.0, 0.0, 0.0, 0.0, 0.0, 0.0)) }, all, "kWh")
        assertEquals(72, dense.bars.size)
        assertEquals(36, dense.categoryTicks.size)
    }

    @Test
    fun `category labels are the inclusive end date of a reading and the model is deterministic`() {
        val slots = listOf(ReadingSlot(LocalDate.parse("2025-07-31"), LocalDate.parse("2025-08-31"), pv(1.0, 0.0, 0.0, 0.0, 0.0, 0.0), false))
        val m = StackedReadingsChart.model(slots, all, "kWh")
        assertEquals(listOf("30/08/25"), m.categoryTicks.map { it.label })
        assertEquals(m, StackedReadingsChart.model(slots, all, "kWh"))
    }
}
