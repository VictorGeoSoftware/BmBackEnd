package com.bm.backend.services.premiumreport

import com.bm.backend.models.DemandSummary
import com.bm.backend.models.DemandWindowStatus
import com.bm.backend.models.ReportDataset
import com.bm.backend.models.StudyHistory
import com.bm.backend.models.StudyHistoryInterval
import com.bm.backend.models.StudyHistoryStatus
import com.bm.backend.models.StudySnapshot
import com.bm.backend.services.premiumreport.PremiumDemandTexts as T
import com.bm.backend.testing.PdfInspection
import com.bm.backend.testing.PremiumReportFixtures
import com.bm.backend.testing.PremiumReportFixtures.activeDataset
import com.bm.backend.testing.PremiumReportFixtures.activeSnapshot
import com.bm.backend.testing.PremiumReportFixtures.history
import com.bm.backend.utils.DemandSummaryCalculator
import com.bm.backend.utils.HistoryCoverageCalculator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Demanded-power page scenarios (spec report-demand-page truth table), synthetic fixtures only. Every scenario
 * asserts that exactly its expected truth-table ids are present and every other id is absent. The catalogue is
 * built from the single source [PremiumDemandTexts]; parameterised rows are matched by structural patterns.
 */
class PremiumDemandSectionTest {
    private val range = "31/07/2025 – 30/07/2026"

    private fun squash(s: String) = s.replace(Regex("\\s+"), "")
    private fun lit(s: String): (String) -> Boolean = { squash(s) in it }
    private fun rx(r: String): (String) -> Boolean = { Regex(r).containsMatchIn(it) }
    private val date = "\\d{2}/\\d{2}/\\d{4}"
    private val num = "[\\d.]+(,\\d+)?"
    private val dash = Regex.escape(T.MISSING)

    /** Truth-table catalogue: id -> predicate over the whitespace-squashed page text. */
    private val catalogue: Map<String, (String) -> Boolean> = linkedMapOf(
        "H0" to lit(T.SECTION),
        "W1" to lit("${T.WINDOW_LINE} ${T.LATEST_WINDOW} ("),
        "W2" to lit("${T.WINDOW_LINE} ${T.NO_DATA}"),
        "H1" to lit(T.STATS_TITLE),
        "C1" to lit("${T.COL_PERIOD} ${T.COL_READINGS} ${T.COL_PEAK} ${T.COL_PEAK_READING} ${T.COL_MEAN}"),
        // C2: period, readings, peak and its inclusive dates.
        "C2" to rx("P[1-6]\\d+$num$date–$date"),
        // C3: period without readings: 0 — — —.
        "C3" to rx("P[1-6]0$dash$dash$dash"),
        "M1" to lit(T.MEAN_WINDOW_INCOMPLETE),
        "M2" to lit(T.MEAN_OVERLAP),
        "S1" to lit(T.STATS_CAPTION),
        "N1" to lit(T.NO_DEMAND_ROWS),
        "N2" to lit(T.DEMAND_UNAVAILABLE),
        "N3" to lit(T.NOTHING_IN_WINDOW),
        "N4" to lit(T.partialWindow(0, 0).substringBefore(" 0")),
        "N5" to { s -> squash(T.crossing(1)) in s || squash(T.crossing(2).substringAfter("2 ")) in s },
        "N6" to lit(T.RECORDED_ZERO),
        "N7" to lit(T.NULL_CELLS),
        "N8" to lit(T.noReadings(emptyList()).substringBefore(" .")),
        "N9" to lit(T.OVERLAP_TAIL),
        "N10" to lit(T.ACTIVE_ZERO_NOTE),
        "K0" to lit(T.COMPARISON_TITLE),
        "KU1" to lit(T.COMPARISON_NO_CONTRACTED),
        "KU2" to lit(T.COMPARISON_NO_READINGS),
        "K1" to lit(T.COMPARISON_BASIS),
        "K2" to lit(T.COMPARISON_CAPTION),
        "K3" to lit(T.COMPARISON_PARTIAL),
        "K4" to lit("${T.COL_PERIOD} ${T.COL_PEAK} ${T.COL_CONTRACTED} ${T.COL_DIFF} ${T.COL_PCT}"),
        // K5: period, peak, contracted, signed difference, percentage.
        "K5" to rx("P[1-6]$num$num(\\+|-)?$num$num%"),
        "K6" to lit(T.NO_CONTRACTED),
        "K7" to lit(T.CONTRACTED_ZERO),
        "K8" to lit(T.NO_READINGS_CELL),
        "G0" to lit(T.READINGS_CHART_TITLE),
        "G2" to lit(T.READINGS_CAPTION),
        "G3" to lit(T.readingsCap(0, 0).substringAfter("0 ").substringBefore(" 0")),
        "G4" to lit(T.READINGS_CHART_UNAVAILABLE),
        "T0" to lit(T.READINGS_TABLE_TITLE),
        "T1" to lit(T.DATES_NOTE),
        "T2" to lit(T.OVERLAP_FOOTNOTE),
        "T3" to lit(T.ACTIVE_ZERO_FOOTNOTE)
    )

    /** Case-insensitive forbidden vocabulary (test I). */
    private val forbidden = listOf("exce", "supera", "sobrepas", "rebasa", "penaliz", "recargo", "optimi", "recomend",
        "aconsej", "ajust", "ahorro", "margen", "holgura", "insuficiente", "adecuad", "cumple", "umbral", "€",
        "factor de potencia", PremiumReportFixtures.CUPS.lowercase())

    private fun ds(rows: List<StudyHistoryInterval>, snapshot: StudySnapshot = activeSnapshot()) = activeDataset(history(rows), snapshot)
    private fun render(ds: ReportDataset) = PdfInspection(PremiumReportPdf.render(listOf(PremiumDemandSection()), ds))
    private fun summary(ds: ReportDataset) =
        DemandSummaryCalculator.calculate(ds.history, HistoryCoverageCalculator.calculate(ds.history), ds.snapshot)

    /** G1 (readings chart unit and bar labels) lives in the chart model; it is present iff the readings chart is. */
    private fun g1(ds: ReportDataset): Boolean =
        DemandChartModel.readings(summary(ds).series)?.let { m ->
            m.unit == T.UNIT && m.bars.all { Regex("^$date – $date: $num ${T.UNIT}$").matches(it.label) }
        } ?: false

    private val baseA = "H0 W1 H1 C1 C2 S1 K0 K1 K2 K4 K5 G0 G1 G2 T0 T1"

    private fun assertScenario(ds: ReportDataset, expected: String, exact: List<String> = emptyList()): String {
        val want = expected.split(' ').filter { it.isNotBlank() }.toSet()
        val known = catalogue.keys + "G1"
        assertTrue(known.containsAll(want), "unknown ids ${want - known}")
        val text = render(ds).text
        val sq = squash(text)
        catalogue.forEach { (id, present) ->
            if (id in want) assertTrue(present(sq), "$id missing in: $text")
            else assertFalse(present(sq), "$id must be absent in: $text")
        }
        assertEquals("G1" in want, g1(ds), "G1")
        exact.forEach { assertTrue(squash(it) in sq, "missing exact '$it' in: $text") }
        val lower = text.lowercase()
        forbidden.forEach { assertFalse(it in lower, "forbidden '$it' in: $text") }
        return text
    }

    private val w1 = "${T.WINDOW_LINE} ${T.LATEST_WINDOW} ($range)"

    @Test
    fun `A complete window shows peaks with dates, means, comparison and one bar per reading`() {
        val ds = ds(PremiumReportFixtures.demandCompleteRows())
        assertTrue(render(ds).portraitPages.all { it })
        val text = assertScenario(ds, baseA, listOf(w1,
            "P1 12 16,103 31/12/2025 – 30/01/2026 14,525",
            "P2 12 15 31/07/2025 – 30/08/2025 15",
            "P6 12 34,75 30/06/2026 – 30/07/2026 33,146",
            "P1 16,103 15,1 +1,003 106,6 %", "P2 15 15,1 -0,1 99,3 %", "P3 18,5 20 -1,5 92,5 %",
            "P4 19,25 20 -0,75 96,3 %", "P5 30 34 -4 88,2 %", "P6 34,75 34,5 +0,25 100,7 %",
            "P1 16,103 kW", "P6 34,75 kW"))
        assertFalse("16,1 " in text && "16,103" !in text)
    }

    @Test
    fun `B incomplete window with a crossing reading shows partial maxima and no means`() {
        val ds = ds(PremiumReportFixtures.demandIncompleteRows())
        val w = summary(ds).window!!
        assertEquals(DemandWindowStatus.INCOMPLETE, w.status)
        val text = assertScenario(ds, "H0 W1 H1 C1 C2 M1 S1 N4 N5 K0 K1 K2 K3 K4 K5 G0 G1 G2 T0 T1",
            listOf(w1, T.partialWindow(w.coveredDays, w.windowDays), T.crossing(1)))
        assertFalse("17" in squash(text).substringAfter(squash(T.STATS_TITLE)).substringBefore(squash(T.STATS_CAPTION)),
            "crossing value is not a peak")
    }

    @Test
    fun `C overlap with a zero-active reading keeps its demand, discloses it and it can be the peak`() {
        val ds = ds(PremiumReportFixtures.demandOverlapZeroActiveRows())
        val text = assertScenario(ds, "H0 W1 H1 C1 C2 M2 S1 N9 N10 K0 K1 K2 K4 K5 G0 G1 G2 T0 T1 T2 T3",
            listOf(w1, T.overlapNote("31/05/2026–29/06/2026"), "P6 13 40 31/05/2026 – 29/06/2026",
                "31/05/2026†° 29/06/2026 30", "P6 40 34,5 +5,5 115,9 %"))
        assertTrue(squash("31/05/2026† 29/06/2026 30") in squash(text), text)
    }

    @Test
    fun `D demand only outside the window shows no figures and explains why`() {
        val ds = ds(PremiumReportFixtures.demandOutsideRows())
        val s = summary(ds)
        assertEquals(StudyHistoryStatus.AVAILABLE, s.status)
        assertEquals(DemandWindowStatus.UNAVAILABLE, s.window!!.status)
        assertScenario(ds, "H0 W1 H1 N3 N5 K0 KU2 G0 G4", listOf(w1, T.crossing(1)))
    }

    @Test
    fun `E snapshot unavailable keeps statistics and replaces the comparison`() {
        val ds = ds(PremiumReportFixtures.demandCompleteRows(), StudySnapshot.unavailable(PremiumReportFixtures.CUPS, "not_captured"))
        val text = assertScenario(ds, "H0 W1 H1 C1 C2 S1 K0 KU1 G0 G1 G2 T0 T1", listOf(w1))
        assertFalse("not_captured" in text)
    }

    @Test
    fun `F missing and zero contracted power are named per period`() {
        val ds = ds(PremiumReportFixtures.demandCompleteRows(), PremiumReportFixtures.demandSnapshotMissingZero())
        assertScenario(ds, "$baseA K6 K7", listOf(w1,
            "P2 15 0 ${T.CONTRACTED_ZERO}", "P3 18,5 ${T.MISSING} ${T.NO_CONTRACTED}", "P1 16,103 15,1 +1,003 106,6 %"))
    }

    @Test
    fun `G recorded zero is shown as 0, never as missing`() {
        val ds = ds(PremiumReportFixtures.demandZeroRows())
        val text = assertScenario(ds, "$baseA N6", listOf(w1, T.RECORDED_ZERO,
            "P1 12 0 31/07/2025 – 30/08/2025 0", "P1 0 15,1 -15,1 0,0 %", "P1 0 kW"))
        assertFalse(T.MISSING in text, "no dash for recorded zeros: $text")
        val s = summary(ds)
        assertTrue(DemandChartModel.comparison(s.periods).bars.all { it.style == ChartBarStyle.ZERO_MARKER })
        assertTrue(DemandChartModel.readings(s.series)!!.bars.all { it.style == ChartBarStyle.ZERO_MARKER })
    }

    @Test
    fun `H inactive periods and null cells show a dash and are never 0`() {
        val ds = ds(PremiumReportFixtures.demandInactiveRows())
        val text = assertScenario(ds, "$baseA C3 N7 N8 K8", listOf(w1, T.noReadings(listOf(4, 5, 6)),
            "P1 10 14,2", "P4 0 ${T.MISSING} ${T.MISSING} ${T.MISSING}",
            "P4 ${T.MISSING} 20 ${T.NO_READINGS_CELL}", "P4 ${T.MISSING}"))
        assertTrue(squash("${T.MISSING} 15 18,5 ${T.MISSING} ${T.MISSING} ${T.MISSING}") in squash(text), text)
    }

    @Test
    fun `L more than 36 readings are capped in the chart and disclosed, the table lists all`() {
        val ds = ds(PremiumReportFixtures.demandLongRows())
        val text = assertScenario(ds, "$baseA G3", listOf(w1, T.readingsCap(36, 73)))
        assertTrue(Regex("Desde Hasta Días").findAll(text).count() >= 2, "header not repeated")
        assertTrue("26/07/2026" in text && "31/07/2025" in text)
    }

    @Test
    fun `N1 no demand rows and N2 other reasons render the short page`() {
        assertScenario(ds(PremiumReportFixtures.demandNoRows()), "H0 W2 N1")
        val text = assertScenario(activeDataset(StudyHistory.unavailable(PremiumReportFixtures.CUPS, "not_captured")), "H0 W2 N2")
        assertFalse("not_captured" in text)
        assertEquals(T.DEMAND_UNAVAILABLE, PremiumDemandSection.unavailableSentence(DemandSummary.unavailable(DemandSummaryCalculator.NO_ANCHOR)))
        assertEquals(T.DEMAND_UNAVAILABLE, PremiumDemandSection.unavailableSentence(DemandSummary.unavailable(DemandSummaryCalculator.CALCULATION_ERROR)))
        assertEquals(T.NO_DEMAND_ROWS, PremiumDemandSection.unavailableSentence(DemandSummary.unavailable(DemandSummaryCalculator.NO_DEMAND_ROWS)))
    }

    @Test
    fun `rendering is deterministic and uses only embedded Liberation Sans`() {
        val ds = ds(PremiumReportFixtures.demandOverlapZeroActiveRows())
        val a = render(ds); val b = render(ds)
        assertEquals(a.text, b.text)
        val s = summary(ds)
        assertEquals(DemandChartModel.readings(s.series), DemandChartModel.readings(s.series))
        assertEquals(DemandChartModel.comparison(s.periods), DemandChartModel.comparison(s.periods))
        assertTrue(a.fonts.isNotEmpty())
        a.fonts.forEach { (name, embedded) ->
            assertTrue(embedded, "font $name not embedded")
            assertTrue("LiberationSans" in name, "unexpected font $name")
        }
    }

    @Test
    fun `I forbidden vocabulary is absent in every scenario`() {
        val missingZero = PremiumReportFixtures.demandSnapshotMissingZero()
        listOf(
            ds(PremiumReportFixtures.demandCompleteRows()), ds(PremiumReportFixtures.demandIncompleteRows()),
            ds(PremiumReportFixtures.demandOverlapZeroActiveRows()), ds(PremiumReportFixtures.demandOutsideRows()),
            ds(PremiumReportFixtures.demandCompleteRows(), StudySnapshot.unavailable(PremiumReportFixtures.CUPS, "x")),
            ds(PremiumReportFixtures.demandCompleteRows(), missingZero), ds(PremiumReportFixtures.demandZeroRows()),
            ds(PremiumReportFixtures.demandInactiveRows()), ds(PremiumReportFixtures.demandNoRows()),
            ds(PremiumReportFixtures.demandLongRows()),
            activeDataset(StudyHistory.unavailable(PremiumReportFixtures.CUPS, "not_captured"))
        ).forEach { d ->
            val text = render(d).text.lowercase()
            forbidden.forEach { assertFalse(it in text, "forbidden '$it' in: $text") }
        }
    }
}
