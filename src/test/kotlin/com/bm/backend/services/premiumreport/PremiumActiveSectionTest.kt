package com.bm.backend.services.premiumreport

import com.bm.backend.models.ReportDataset
import com.bm.backend.models.StudyHistory
import com.bm.backend.testing.PdfInspection
import com.bm.backend.testing.PremiumReportFixtures
import com.bm.backend.testing.PremiumReportFixtures.activeDataset
import com.bm.backend.testing.PremiumReportFixtures.history
import com.bm.backend.utils.HistoryCoverageCalculator
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
    fun `A two-year sample shows firm figures, YoY, overlap disclosure and the high-interval window`() {
        val pdf = render(activeDataset(history(PremiumReportFixtures.sampleRows())))
        assertTrue(pdf.isPdf)
        assertTrue(pdf.portraitPages.all { it })
        val text = pdf.text
        assertHas(text, "ENERGÍA ACTIVA", "Histórico disponible: 31/08/2023 – 30/07/2026",
            "72.677", "64.948", "7.201", "8.225", "7.467", "9.031", "3.684", "37.069", "9,9 %",
            "Variación interanual: +11,9 %",
            "Lecturas solapadas 31/05/2026–29/06/2026: una de ellas sin consumo; no altera el total.",
            "Intervalos de mayor consumo medio diario",
            "Calculado sobre últimos 12 meses (31/07/2025 – 30/07/2026) (Completo)",
            "kWh/día", "P1 7.201 kWh (9,9 %)",
            "El histórico cubre últimos 12 meses (31/07/2025 – 30/07/2026); la fuente no indica su periodo de referencia.")
        // kWh/día in the high-interval table always has exactly one decimal.
        val perDay = Regex("\\d{2}/\\d{2}/\\d{4} \\d{2}/\\d{2}/\\d{4} \\d+ [\\d.]+ ([\\d.,]+) P\\d").findAll(text).map { it.groupValues[1] }.toList()
        assertTrue(perDay.size >= 3, text)
        perDay.forEach { assertTrue(Regex("^[\\d.]+,\\d$").matches(it), "kWh/día '$it' must have one decimal") }
        assertHasNot(text, "Histórico inferior a 12 meses", "Parcial:", "Sin cifra firme")
    }

    @Test
    fun `B partial history shows partial totals without shares, distribution or annualised figure`() {
        val text = render(activeDataset(history(PremiumReportFixtures.partialRows()))).text
        assertHas(text, "Parcial: 39.200", "Histórico inferior a 12 meses completos: las cifras se muestran como parciales.",
            "Distribución por periodo no disponible: no hay 12 meses completos.",
            "Variación interanual no disponible")
        assertHasNot(text, " %)", "Variación interanual: ", "58.800", "Total: ", "El histórico cubre")
        // 39.200 kWh over 8 months must never be annualised (×1,5 = 58.800).
    }

    @Test
    fun `C overlap-blocked latest window has no firm figure and discloses the fallback window`() {
        val text = render(activeDataset(history(PremiumReportFixtures.overlapRows()))).text
        assertHas(text, "Sin cifra firme: lecturas solapadas sin resolver",
            "Distribución por periodo no disponible: lecturas solapadas sin resolver.")
        assertTrue("Calculado sobre 12 meses anteriores (31/07/2024 – 30/07/2025)" in text ||
            "No hay intervalos disponibles para clasificar." in text, text)
        assertHas(text, "Variación interanual no disponible (los últimos 12 meses tienen lecturas solapadas sin resolver).")
        assertHasNot(text, "72.677", "Variación interanual: +",
            "Histórico inferior a 12 meses completos", "no están completos", "no está completa", "El histórico cubre")
        assertHas(text, "Comparación no posible: los últimos 12 meses tienen lecturas solapadas sin resolver.")
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
        assertHas(text, "ENERGÍA ACTIVA", "Sin datos", "No hay intervalos disponibles para clasificar.",
            "Comparación no posible: no hay histórico de energía activa.")
        assertHasNot(text, "no está completa", "El histórico cubre")
        assertHasNot(text, "kWh/día", "not_captured", "Histórico inferior a 12 meses completos")
    }

    @Test
    fun `D2 latest window empty only because its reading crosses the boundary is not called no-history`() {
        val d = java.time.LocalDate.parse("2025-01-01")
        val rows = listOf(PremiumReportFixtures.row(d, java.time.LocalDate.parse("2026-07-31"), PremiumReportFixtures.pv(1000.0, 1000.0, 1000.0, 1000.0, 1000.0, 1000.0)))
        val text = render(activeDataset(history(rows))).text
        assertHas(text, "Sin datos", "Comparación no posible: la ventana de 12 meses no está completa",
            "Variación interanual no disponible (los últimos 12 meses no están completos).",
            "Distribución por periodo no disponible: no hay 12 meses completos.", "No hay intervalos disponibles para clasificar.")
        assertHasNot(text, "no hay histórico de energía activa", "El histórico cubre", "Histórico inferior a 12 meses completos")
    }

    @Test
    fun `E comparisons keep source and history values with provenance`() {
        val text = render(activeDataset(history(PremiumReportFixtures.sampleRows()))).text
        assertHas(text, "SIPS (distribuidora)", "Consumo anual del estudio básico", "37.169", "-100", "+201",
            "El histórico cubre últimos 12 meses (31/07/2025 – 30/07/2026); la fuente no indica su periodo de referencia.")
        val partial = render(activeDataset(history(PremiumReportFixtures.partialRows()))).text
        assertHas(partial, "Comparación no posible: la ventana de 12 meses no está completa", "37.169", "72.777")
    }

    @Test
    fun `F long history flows across pages with every row and the chart cap disclosed`() {
        val rows = PremiumReportFixtures.longRows()
        val pdf = render(activeDataset(history(rows)))
        assertTrue(pdf.pageCount >= 2)
        val text = pdf.text
        val fmt = DateTimeFormatter.ofPattern("dd/MM/yyyy")
        rows.forEach { assertTrue(it.startDate.format(fmt) in text, "missing ${it.startDate}") }
        assertHas(text, "Se muestran las 36 lecturas más recientes de 40.")
        assertTrue(Regex("Desde Hasta Días").findAll(text).count() >= 2, "header not repeated")
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
        val h = ds.history
        assertEquals(ActiveChartModel.readings(h, HistoryCoverageCalculator.calculate(h)), ActiveChartModel.readings(h, HistoryCoverageCalculator.calculate(h)))
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
