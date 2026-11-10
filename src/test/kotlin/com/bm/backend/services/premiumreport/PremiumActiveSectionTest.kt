package com.bm.backend.services.premiumreport

import com.bm.backend.models.ReportDataset
import com.bm.backend.models.StudyHistory
import com.bm.backend.testing.PdfInspection
import com.bm.backend.testing.PremiumReportFixtures
import com.bm.backend.testing.PremiumReportFixtures.activeDataset
import com.bm.backend.testing.PremiumReportFixtures.history
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.format.DateTimeFormatter

/** Active-energy page scenarios A–I (spec report-active-page), synthetic fixtures only. */
class PremiumActiveSectionTest {
    private fun render(ds: ReportDataset) = PdfInspection(PremiumReportPdf.render(listOf(PremiumActiveSection()), ds))
    private fun assertHas(text: String, vararg parts: String) = parts.forEach { assertTrue(it in text, "missing '$it' in: $text") }
    private fun assertHasNot(text: String, vararg parts: String) = parts.forEach { assertFalse(it in text, "unexpected '$it' in: $text") }

    @Test
    fun `A two-year sample shows firm figures, YoY, overlap disclosure and the per-reading chart`() {
        val pdf = render(activeDataset(history(PremiumReportFixtures.sampleRows())))
        assertTrue(pdf.isPdf)
        assertTrue(pdf.portraitPages.all { it })
        val text = pdf.text
        assertHas(text, "ENERGÍA ACTIVA", "Histórico disponible: 31/08/2023 – 30/07/2026",
            "72.677", "64.948", "7.201", "8.225", "7.467", "9.031", "3.684", "37.069", "9,9 %",
            "Variación interanual: +11,9 %",
            "Lecturas solapadas 31/05/2026–29/06/2026: una de ellas sin consumo; no altera el total.",
            "P1 7.201 kWh (9,9 %)", "Consumo de energía activa por lectura")
        // Feedback 2026-11-03: daily averages, high intervals and source comparisons are gone.
        assertHasNot(text, "Histórico inferior a 12 meses", "Parcial:", "Sin cifra firme", "kWh/día",
            "Consumo medio diario por lectura", "Intervalos de mayor consumo medio diario", "Comparación con fuentes anuales",
            "SIPS (distribuidora)", "Consumo anual del estudio básico", "El histórico cubre", "Cada barra es una lectura")
    }

    @Test
    fun `B partial history shows available distribution without annualization or invented previous consumption`() {
        val text = render(activeDataset(history(PremiumReportFixtures.partialRows()))).text
        assertHas(text, "Parcial: 39.200", "Histórico inferior a 12 meses completos: las cifras se muestran como parciales.",
            "P1 4.000 kWh (10,2 %)",
            "Distribución calculada sobre el consumo disponible de los últimos 12 meses (cobertura parcial).",
            "Variación interanual no disponible (sin consumo disponible en los 12 meses anteriores).")
        assertHasNot(text, "Distribución por periodo no disponible", "Variación interanual: ", "58.800", "Total: ", "El histórico cubre")
        // 39.200 kWh over 8 months must never be annualised (×1,5 = 58.800).
    }

    @Test
    fun `partial August windows show comparison and distribution from the same table totals`() {
        val ds = PremiumReportFixtures.tariffDataset(rows = PremiumReportFixtures.partialComparisonRows())
        val pdf = render(ds)
        val text = pdf.text
        assertHas(text, "19/08/2025 – 18/08/2026", "19/08/2024 – 18/08/2025",
            "363 / 365", "331 / 365", "Parcial: 5.037", "Parcial: 4.265",
            "Variación interanual: +18,1 % (datos parciales)",
            "Comparación de los consumos disponibles en ambas ventanas; sin extrapolar ni ajustar por días cubiertos.",
            "Distribución calculada sobre el consumo disponible de los últimos 12 meses (cobertura parcial).",
            "P1 1.136 kWh (22,6 %)", "P2 1.242 kWh (24,7 %)", "P3 2.659 kWh (52,8 %)",
            "Diferencia consumo últimos dos años")
        assertTrue(text.substringAfter("Diferencia consumo últimos dos años").trimStart()
            .startsWith("+18,1 % (datos parciales)"), text)
        assertHasNot(text, "Variación interanual no disponible", "Distribución por periodo no disponible",
            "P4 (kWh)", "P4 —", "no están completos", "no hay 12 meses completos")
        assertTrue(pdf.portraitPages.all { it })
    }

    @Test
    fun `C overlap-blocked latest window has no firm figure and discloses the fallback window`() {
        val text = render(activeDataset(history(PremiumReportFixtures.overlapRows()))).text
        assertHas(text, "Sin cifra firme: lecturas solapadas sin resolver",
            "Distribución por periodo no disponible: lecturas solapadas sin resolver.")
        assertHas(text, "Variación interanual no disponible (los últimos 12 meses tienen lecturas solapadas sin resolver).")
        assertHasNot(text, "72.677", "Variación interanual: +",
            "Histórico inferior a 12 meses completos", "no están completos", "no está completa", "El histórico cubre", "Comparación no posible")
    }

    @Test
    fun `C2 overlap-blocked previous window explains the missing YoY`() {
        val text = render(activeDataset(history(PremiumReportFixtures.previousOverlapRows()))).text
        assertHas(text, "72.677", "Sin cifra firme: lecturas solapadas sin resolver",
            "Variación interanual no disponible (los 12 meses anteriores tienen lecturas solapadas sin resolver).")
        assertHasNot(text, "64.948", "Variación interanual: +", "no están completos", "Histórico inferior a 12 meses completos")
    }

    @Test
    fun `D unavailable history renders the empty texts without charts`() {
        val pdf = render(activeDataset(StudyHistory.unavailable(PremiumReportFixtures.CUPS, "not_captured")))
        val text = pdf.text
        assertHas(text, "ENERGÍA ACTIVA", "Sin datos")
        assertHasNot(text, "no está completa", "El histórico cubre", "Comparación no posible", PremiumActiveTexts.READINGS_CHART_TITLE)
        assertHasNot(text, "kWh/día", "not_captured", "Histórico inferior a 12 meses completos")
    }

    @Test
    fun `D2 latest window empty only because its reading crosses the boundary is not called no-history`() {
        val d = java.time.LocalDate.parse("2025-01-01")
        val rows = listOf(PremiumReportFixtures.row(d, java.time.LocalDate.parse("2026-07-31"), PremiumReportFixtures.pv(1000.0, 1000.0, 1000.0, 1000.0, 1000.0, 1000.0)))
        val text = render(activeDataset(history(rows))).text
        assertHas(text, "Sin datos",
            "Variación interanual no disponible (sin consumo disponible en los últimos 12 meses).",
            "Distribución por periodo no disponible: sin consumo disponible en los últimos 12 meses.")
        assertHasNot(text, "no hay histórico de energía activa", "El histórico cubre", "Histórico inferior a 12 meses completos")
    }

    @Test
    fun `F long history lists and charts exactly the readings of the last 24 months`() {
        val rows = PremiumReportFixtures.longRows()
        val text = render(activeDataset(history(rows))).text
        val fmt = DateTimeFormatter.ofPattern("dd/MM/yyyy")
        val start = java.time.LocalDate.parse("2024-07-31")
        val (recent, older) = rows.partition { it.startDate >= start }
        assertEquals(24, recent.size)
        recent.forEach { assertTrue(it.startDate.format(fmt) in text, "missing ${it.startDate}") }
        // The first row's date also opens the "Histórico disponible" line; the other older rows must not be listed.
        older.drop(1).forEach { assertFalse(it.startDate.format(fmt) in text, "unexpected ${it.startDate}") }
        assertHasNot(text, "lecturas más recientes", "Cada barra es una lectura")
    }

    @Test
    fun `G null cells show a dash and a zero window shows 0 without shares`() {
        val base = java.time.LocalDate.parse("2025-07-31")
        val rows = PremiumReportFixtures.monthly(base, 0, 12) { PremiumReportFixtures.ZEROS }.toMutableList()
        rows[3] = rows[3].copy(active = PremiumReportFixtures.pv(0.0, null, 0.0, 0.0, 0.0, 0.0))
        val text = render(activeDataset(history(rows))).text
        assertHas(text, "—", "Distribución por periodo no disponible: el consumo total es 0.")
        assertHasNot(text, "P1 0 kWh")
        assertFalse(Regex("(?<![0-9,])0,0 %").containsMatchIn(text), "zero window must not show shares: $text")
        assertTrue(Regex("31/10/2025 29/11/2025 30 0 — 0 0 0 0 0").containsMatchIn(text), text)
    }

    @Test
    fun `H rendering is deterministic and uses only embedded Liberation Sans`() {
        val ds = activeDataset(history(PremiumReportFixtures.sampleRows()))
        val a = render(ds); val b = render(ds)
        assertEquals(a.text, b.text)
        val fonts = a.fonts
        fonts.forEach { (name, embedded) ->
            assertTrue(embedded, "font $name not embedded")
            assertTrue("LiberationSans" in name, "unexpected font $name")
        }
    }

    @Test
    fun `I cover and active page render together and CUPS is not repeated on the page`() {
        val ds = activeDataset(history(PremiumReportFixtures.sampleRows()))
        val text = PdfInspection(PremiumReportPdf.render(listOf(PremiumActiveSection()), ds)).text
        assertHasNot(text, PremiumReportFixtures.CUPS, "excesivo", "penalización", "recomend", "optimi", "exceso")
        val both = PdfInspection(PremiumReportPdf.render(listOf(PremiumCoverSection(PremiumReportFixtures.CLOCK), PremiumActiveSection()), ds))
        assertTrue(both.pageCount >= 2)
    }
}
