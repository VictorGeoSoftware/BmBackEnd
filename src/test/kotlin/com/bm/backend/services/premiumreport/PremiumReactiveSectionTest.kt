package com.bm.backend.services.premiumreport

import com.bm.backend.models.ReactiveSummary
import com.bm.backend.models.ReportDataset
import com.bm.backend.models.StudyHistory
import com.bm.backend.models.StudyHistoryInterval
import com.bm.backend.services.premiumreport.PremiumReactiveTexts as T
import com.bm.backend.testing.PdfInspection
import com.bm.backend.testing.PremiumReportFixtures
import com.bm.backend.testing.PremiumReportFixtures.activeDataset
import com.bm.backend.testing.PremiumReportFixtures.history
import com.bm.backend.utils.HistoryCoverageCalculator
import com.bm.backend.utils.ReactiveSummaryCalculator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Reactive-energy page scenarios A–J (spec report-reactive-page), synthetic fixtures only. Every scenario asserts
 * that its expected truth-table sentences are present and every other truth-table sentence is absent; the texts
 * come from the single source [PremiumReactiveTexts].
 */
class PremiumReactiveSectionTest {
    private val range = "31/07/2025 – 30/07/2026"

    /**
     * Truth-table catalog: id -> probe. A probe is the exact sentence for fixed texts and a characteristic fixed
     * fragment for parameterised ones (asserted absent when the id is not expected). Expected parameterised
     * sentences are additionally asserted in full through [exact].
     */
    private val catalog: Map<String, String> = linkedMapOf(
        "H0" to T.SECTION,
        "W1" to "${T.WINDOW_LINE} ${T.LATEST_WINDOW} (",
        "W2" to "${T.WINDOW_LINE} ${T.NO_DATA}",
        "H1" to T.WINDOW_TITLE,
        "C1" to "${T.COL_COVERED_DAYS} P1${T.UNIT_SUFFIX}",
        "C3" to T.STATUS_COMPLETE,
        "C4" to "${T.PARTIAL_PREFIX} ",
        "C5" to T.OVERLAP_NO_FIGURE,
        "C6" to "${T.STATUS_UNAVAILABLE} ${T.NO_DATA}",
        "N1" to T.NO_REACTIVE_ROWS,
        "N2" to T.REACTIVE_UNAVAILABLE,
        "N3" to T.NOTHING_IN_WINDOW,
        "N4" to "Ventana parcial: las lecturas cubren",
        "N5a" to T.CROSSING_ONE,
        "N5b" to "lecturas cruzan el límite de la ventana y no se suman.",
        "N6" to T.RECORDED_ZERO,
        "N7" to T.NULL_CELLS,
        "N8" to ": ${PremiumActiveTexts.OVERLAP_ZERO_NOTE}",
        "N9" to ": ${PremiumActiveTexts.OVERLAP_BLOCKING_NOTE}",
        "D0" to T.DISTRIBUTION_TITLE,
        "D1" to " ${T.UNIT} (",
        "D2" to T.DISTRIBUTION_NOT_COMPLETE,
        "D3" to T.DISTRIBUTION_OVERLAP,
        "D4" to T.DISTRIBUTION_ZERO,
        "Q0" to T.RATIO_TITLE,
        "B1" to T.basis("").substringBefore("("),
        "B2" to "${T.COL_REACTIVE} ${T.COL_ACTIVE} ${T.COL_RATIO}",
        "R1" to T.RATIO_PERIOD_MISSING,
        "R2" to T.RATIO_NULL_CELLS,
        "R3" to T.RATIO_ACTIVE_ZERO,
        "U0" to T.RATIO_UNAVAILABLE,
        "U1" to T.RATIO_REACTIVE_OVERLAP,
        "U2" to T.RATIO_REACTIVE_INCOMPLETE,
        "U3" to T.RATIO_ACTIVE_UNAVAILABLE,
        "U4" to T.RATIO_ACTIVE_OVERLAP,
        "U5" to T.RATIO_ACTIVE_INCOMPLETE,
        "G0" to T.READINGS_CHART_TITLE,
        "G1" to T.UNIT_PER_DAY,
        "G2" to T.READINGS_CAPTION,
        "G3" to T.readingsCap(0, 0).substringAfter("0 ").substringBefore(" 0"),
        "G4" to T.READINGS_CHART_UNAVAILABLE,
        "T0" to T.READINGS_TABLE_TITLE,
        "T1" to T.DATES_NOTE,
        "T2" to T.OVERLAP_FOOTNOTE
    )

    private val forbidden = listOf("penaliz", "factor de potencia", "cos", "φ", "exceso", "cumple", "umbral", "33 %", "€",
        "optimi", "recomend", "Información descriptiva", PremiumReportFixtures.CUPS)

    private fun ds(rows: List<StudyHistoryInterval>) = activeDataset(history(rows))
    private fun render(ds: ReportDataset) = PdfInspection(PremiumReportPdf.render(listOf(PremiumReactiveSection()), ds))
    private fun summary(ds: ReportDataset) = ReactiveSummaryCalculator.calculate(ds.history, HistoryCoverageCalculator.calculate(ds.history))

    private fun ids(spec: String): Set<String> = spec.split(' ').filter { it.isNotBlank() }.flatMap { id ->
        when (id) {
            "G0–G2" -> listOf("G0", "G1", "G2")
            else -> listOf(id)
        }
    }.toSet()

    /** Asserts the exact expected set: expected probes + exact sentences present, every other catalog probe absent. */
    private fun assertSentences(text: String, expected: String, exact: List<String> = emptyList()) {
        val want = ids(expected)
        assertTrue(catalog.keys.containsAll(want), "unknown ids ${want - catalog.keys}")
        // Table cells may be extracted without separating spaces, so probes compare with whitespace removed.
        val squashed = squash(text)
        catalog.forEach { (id, probe) ->
            if (id in want) assertTrue(squash(probe) in squashed, "$id missing '$probe' in: $text")
            else assertFalse(squash(probe) in squashed, "$id must be absent but found '$probe' in: $text")
        }
        exact.forEach { assertTrue(squash(it) in squashed, "missing exact '$it' in: $text") }
        forbidden.forEach { assertFalse(it in text, "forbidden '$it' in: $text") }
    }

    private fun squash(s: String) = s.replace(Regex("\\s+"), "")
    private fun has(text: String, part: String) = squash(part) in squash(text)

    private val w1 = "${T.WINDOW_LINE} ${T.windowValue(range)}"
    private val b1 = T.basis(range)

    @Test
    fun `A complete window shows totals, shares, ratios, both charts and a neutral zero overlap`() {
        val ds = ds(PremiumReportFixtures.reactiveCompleteRows())
        val pdf = render(ds)
        assertTrue(pdf.portraitPages.all { it })
        val text = pdf.text
        assertSentences(text, "H0 W1 H1 C1 C3 N8 D0 D1 Q0 B1 B2 G0–G2 T0 T1",
            listOf(w1, b1, T.overlapNote("31/05/2026–29/06/2026", true), "365 / 365",
                "P1 1.200 kVArh (11,0 %)", "P2 1.440 kVArh (13,2 %)", "P3 1.080 kVArh (9,9 %)",
                "P4 1.800 kVArh (16,5 %)", "P5 600 kVArh (5,5 %)", "P6 4.800 kVArh (44,0 %)", "10.920"))
        // Quotient table: hand-computed kVArh / kWh with 2 decimals.
        listOf("P1 1.200 4.800 0,25", "P2 1.440 6.000 0,24", "P3 1.080 5.400 0,20", "P4 1.800 7.200 0,25",
            "P5 600 3.000 0,20", "P6 4.800 30.000 0,16", "Total 10.920 56.400 0,19").forEach {
            assertTrue(has(text, it), "missing ratio row '$it' in: $text")
        }
    }

    @Test
    fun `B recorded zero is a figure, not missing data, and the quotient is 0,00`() {
        val text = render(ds(PremiumReportFixtures.reactiveZeroRows())).text
        assertSentences(text, "H0 W1 H1 C1 C3 N6 D0 D4 Q0 B1 B2 G0–G2 T0 T1", listOf(w1, b1, "P1 0 4.800 0,00", "Total 0 56.400 0,00"))
    }

    @Test
    fun `C missing reactive renders the short page only`() {
        val text = render(ds(PremiumReportFixtures.reactiveMissingRows())).text
        assertSentences(text, "H0 W2 N1")
    }

    @Test
    fun `C2 reactive only outside the window is handled explicitly`() {
        val ds = ds(PremiumReportFixtures.reactiveOutsideRows())
        val s = summary(ds)
        assertEquals(com.bm.backend.models.StudyHistoryStatus.AVAILABLE, s.status)
        assertEquals(com.bm.backend.models.ReactiveWindowStatus.UNAVAILABLE, s.window!!.status)
        val text = render(ds).text
        assertSentences(text, "H0 W1 H1 C1 C6 N3 N5a D0 D2 Q0 B1 U0 U2 G0 G4",
            listOf(w1, b1, "${T.RATIO_UNAVAILABLE} ${T.RATIO_REACTIVE_INCOMPLETE}"))
    }

    @Test
    fun `D incomplete window shows partial figures without shares or quotient`() {
        val ds = ds(PremiumReportFixtures.reactiveIncompleteRows())
        val w = summary(ds).window!!
        val text = render(ds).text
        assertSentences(text, "H0 W1 H1 C1 C4 N4 D0 D2 Q0 B1 U0 U2 G0–G2 T0 T1",
            listOf(w1, b1, T.partialWindow(w.coveredDays, w.windowDays), "${T.RATIO_UNAVAILABLE} ${T.RATIO_REACTIVE_INCOMPLETE}",
                "${T.PARTIAL_PREFIX} ${PremiumReportFormat.kwh(w.partialTotal!!)}", "243 / 365"))
        assertFalse("%" in text, "no shares when partial")
    }

    @Test
    fun `E overlap-blocked window has no firm figure and marks overlapping readings`() {
        val text = render(ds(PremiumReportFixtures.reactiveOverlapRows())).text
        assertSentences(text, "H0 W1 H1 C1 C5 N9 D0 D3 Q0 B1 U0 U1 G0–G2 T0 T1 T2",
            listOf(w1, b1, T.overlapNote("15/01/2026–30/01/2026", false), T.overlapNote("31/01/2026–09/02/2026", false), "${T.RATIO_UNAVAILABLE} ${T.RATIO_REACTIVE_OVERLAP}"))
        assertFalse("10.920" in text)
        assertTrue(Regex("\\d{2}/\\d{2}/\\d{4}${T.OVERLAP_MARK}").containsMatchIn(text), text)
    }

    @Test
    fun `F active basis unavailable, overlapping or incomplete replaces the quotient with its reason`() {
        val cases = listOf(
            PremiumReportFixtures.reactiveNoActiveRows() to ("U3" to T.RATIO_ACTIVE_UNAVAILABLE),
            PremiumReportFixtures.reactiveActiveOverlapRows() to ("U4" to T.RATIO_ACTIVE_OVERLAP),
            PremiumReportFixtures.reactiveActiveIncompleteRows() to ("U5" to T.RATIO_ACTIVE_INCOMPLETE)
        )
        cases.forEach { (rows, reason) ->
            val text = render(ds(rows)).text
            assertSentences(text, "H0 W1 H1 C1 C3 D0 D1 Q0 B1 U0 ${reason.first} G0–G2 T0 T1",
                listOf(w1, "${T.RATIO_UNAVAILABLE} ${reason.second}"))
        }
    }

    @Test
    fun `G null cells show a dash, are never 0 and block the affected quotients`() {
        val text = render(ds(PremiumReportFixtures.reactiveNullRows())).text
        assertSentences(text, "H0 W1 H1 C1 C3 N7 D0 D1 Q0 B1 B2 R2 G0–G2 T0 T1", listOf(w1, b1))
        val sq = squash(text)
        val nullCells = Regex.escape(squash(T.RATIO_NULL_CELLS))
        (4..6).forEach { p -> assertTrue(Regex("P$p[\\d.]+$nullCells").containsMatchIn(sq), "P$p: $text") }
        assertTrue(Regex("Total[\\d.]+$nullCells").containsMatchIn(sq), text)
        assertTrue("10012090———310" in sq, text)
        assertTrue(Regex("P1[\\d.]+0,25").containsMatchIn(sq), text)
        assertFalse(Regex("P4[\\d.]+0,\\d\\d").containsMatchIn(sq), text)
    }

    @Test
    fun `L more than 36 readings are capped in the chart and disclosed, the table lists all`() {
        val text = render(ds(PremiumReportFixtures.reactiveLongRows())).text
        assertSentences(text, "H0 W1 H1 C1 C3 D0 D1 Q0 B1 B2 G0–G2 G3 T0 T1", listOf(w1, b1, T.readingsCap(36, 73)))
        assertTrue(Regex("Desde Hasta Días").findAll(text).count() >= 2, "header not repeated")
        assertTrue("26/07/2026" in text && "31/07/2025" in text)
    }

    @Test
    fun `N2 unavailable history renders the short page with the generic sentence`() {
        val text = render(activeDataset(StudyHistory.unavailable(PremiumReportFixtures.CUPS, "not_captured"))).text
        assertSentences(text, "H0 W2 N2")
        assertFalse("not_captured" in text)
        val noAnchor: ReactiveSummary = ReactiveSummary.unavailable(ReactiveSummaryCalculator.NO_ANCHOR)
        assertEquals(T.REACTIVE_UNAVAILABLE, PremiumReactiveSection.unavailableSentence(noAnchor))
        assertEquals(T.REACTIVE_UNAVAILABLE, PremiumReactiveSection.unavailableSentence(ReactiveSummary.unavailable(ReactiveSummaryCalculator.CALCULATION_ERROR)))
        assertEquals(T.NO_REACTIVE_ROWS, PremiumReactiveSection.unavailableSentence(ReactiveSummary.unavailable(ReactiveSummaryCalculator.NO_REACTIVE_ROWS)))
    }

    @Test
    fun `H rendering is deterministic and uses only embedded Liberation Sans`() {
        val ds = ds(PremiumReportFixtures.reactiveCompleteRows())
        val a = render(ds); val b = render(ds)
        assertEquals(a.text, b.text)
        val s = summary(ds)
        assertEquals(ReactiveChartModel.readings(s.series), ReactiveChartModel.readings(s.series))
        assertEquals(ReactiveChartModel.distribution(s.window!!), ReactiveChartModel.distribution(s.window!!))
        assertTrue(a.fonts.isNotEmpty())
        a.fonts.forEach { (name, embedded) ->
            assertTrue(embedded, "font $name not embedded")
            assertTrue("LiberationSans" in name, "unexpected font $name")
        }
    }

    @Test
    fun `I forbidden vocabulary is absent in every scenario`() {
        listOf(
            PremiumReportFixtures.reactiveCompleteRows(), PremiumReportFixtures.reactiveZeroRows(),
            PremiumReportFixtures.reactiveMissingRows(), PremiumReportFixtures.reactiveOutsideRows(),
            PremiumReportFixtures.reactiveIncompleteRows(), PremiumReportFixtures.reactiveOverlapRows(),
            PremiumReportFixtures.reactiveNoActiveRows(), PremiumReportFixtures.reactiveNullRows(),
            PremiumReportFixtures.reactiveLongRows()
        ).forEach { rows ->
            val text = render(ds(rows)).text.lowercase()
            forbidden.forEach { assertFalse(it.lowercase() in text, "forbidden '$it' in: $text") }
        }
    }
}
