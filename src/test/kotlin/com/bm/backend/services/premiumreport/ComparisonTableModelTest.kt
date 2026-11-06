package com.bm.backend.services.premiumreport

import com.bm.backend.models.ComparatorReportPdfRequest
import com.bm.backend.models.ComparatorReportProposal
import com.bm.backend.services.ComparatorReportPdfTexts as T
import com.bm.backend.testing.ReportComparisonFixtures as F
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.Locale

/**
 * Catalogue test: for every scenario the model's ordered cell texts equal a list built independently from the
 * spec's truth table (B0 is the section bar, checked in the section test).
 */
class ComparisonTableModelTest {

    private fun f6(x: Double) = String.format(Locale.US, "%.6f", x)
    private fun f3(x: Double) = String.format(Locale.US, "%.3f", x)
    private fun cur(v: String) = if (v.isBlank() || v == "-" || v.endsWith("€") || v.endsWith("%")) v else "$v €"

    /** Truth table H1–A4 for one page, written out row by row. */
    private fun expected(c: ComparatorReportPdfRequest, offers: List<ComparatorReportProposal>): List<String> {
        val cc = c.customerConditions
        val out = mutableListOf(c.tariffName, T.ANNUAL_CONSUMPTION_COLUMN, T.CURRENT_CONDITIONS_COLUMN)
        out += offers.map { it.title }
        c.powerTermRows.forEachIndexed { i, r ->
            if (i == 0) out += T.POWER_TERM_SIDE_TITLE
            out += "${r.period} ${f3(r.value)} kW"
            out += "${f6(cc.powerTermItems.getOrElse(i) { 0.0 })} €/kW año"
            out += offers.map { f6(it.powerTermItems.getOrElse(i) { 0.0 }) }
        }
        out += T.POWER_TERM_ANNUAL_COST_LABEL; out += cur(cc.annualPowerTermCost); out += offers.map { cur(it.annualPowerTermCost) }
        c.energyConsumedRows.forEachIndexed { i, r ->
            if (i == 0) out += T.ENERGY_CONSUMED_SIDE_TITLE + "\n\n" + "${c.energyConsumedRows.sumOf { it.value }} kWh"
            out += "${r.period} ${r.value} kWh"
            out += "${f6(cc.consumedEnergyItems.getOrElse(i) { 0.0 })} €/kWh"
            out += offers.map { f6(it.consumedEnergyItems.getOrElse(i) { 0.0 }) }
        }
        out += T.ENERGY_TERM_ANNUAL_COST_LABEL; out += cur(cc.annualEnergyCost); out += offers.map { cur(it.annualEnergyCost) }
        out += T.EXTRA_SERVICES_LABEL; out += cur(cc.extraServices); out += offers.map { cur(it.extraServices) }
        out += if (c.impuestoElectrico.isBlank() || c.impuestoElectrico == "-") T.ELECTRIC_TAX_LABEL else "${T.ELECTRIC_TAX_LABEL} - ${c.impuestoElectrico}"
        out += cur(cc.electricalTax); out += offers.map { cur(it.electricalTax) }
        out += if (c.iva.isBlank() || c.iva == "-") T.IVA_LABEL else "${T.IVA_LABEL} - ${c.iva}"
        out += cur(cc.iva); out += offers.map { cur(it.iva) }
        out += T.ANNUAL_INVOICE_COST_LABEL; out += cur(cc.totalAnnualPrice); out += offers.map { cur(it.totalAnnualPrice) }
        out += ""; out += T.ANNUAL_SAVINGS_LABEL
        out += offers.map { o ->
            val d = o.annualPriceDifference; val p = o.annualSavingsPercentage
            when {
                d != null && p != null -> cur(d) + "\n" + "$p%"   // A1
                d != null -> cur(d)                                 // A2
                p != null -> "$p%"                                  // A3
                else -> ""                                          // A4
            }
        }
        return out
    }

    private fun assertCatalogue(c: ComparatorReportPdfRequest, expectedPages: Int) {
        val pages = ComparisonTableModel.pages(c)
        assertEquals(expectedPages, pages.size)
        assertEquals(c.proposals, pages.flatMap { it.offers })
        pages.forEach { page -> assertEquals(expected(c, page.offers), page.cells.map { it.text }) }
    }

    @Test fun `A one offer saving`() = assertCatalogue(F.pageRequest(), 1)
    @Test fun `B mixed signs`() = assertCatalogue(F.pageScenarioB(), 1)
    @Test fun `C null delta variants`() = assertCatalogue(F.pageScenarioC(), 1)
    @Test fun `D extra services verbatim`() = assertCatalogue(F.pageScenarioD(), 1)
    @Test fun `E seven offers`() = assertCatalogue(F.pageScenarioOffers(7), 1)
    @Test fun `F eight and fifteen offers`() { assertCatalogue(F.pageScenarioOffers(8), 2); assertCatalogue(F.pageScenarioOffers(15), 3) }
    @Test fun `G long strings`() = assertCatalogue(F.pageScenarioG(), 1)
    @Test fun `H six periods`() = assertCatalogue(F.pageScenarioH(), 1)
    @Test fun `Q3 short item lists`() = assertCatalogue(F.pageScenarioShortItems(), 1)

    @Test
    fun `literal expectations for key scenarios`() {
        val a = ComparisonTableModel.pages(F.pageRequest()).single().cells.map { it.text }
        assertEquals(listOf(
            "fixed", "DATOS DISTRIBUIDORA", "CONDICIONES ACTUALES", "Oferta Ficticia A",
            "POTENCIA\nCONTRATADA", "P1 15.000 kW", "0.100000 €/kW año", "0.090000",
            "P2 15.000 kW", "0.050000 €/kW año", "0.040000",
            "Coste anual término de potencia", "821.25 €", "711.75 €",
            "TOTAL ENERGÍA ANUAL\nCONSUMIDA\n\n3000 kWh", "P1 1000 kWh", "0.200000 €/kWh", "0.180000",
            "P2 2000 kWh", "0.100000 €/kWh", "0.090000",
            "Coste anual término de energía", "400.00 €", "360.00 €",
            "SERVICIOS EXTRA", "0.00 €", "12.00 €",
            "IMPUESTO ELÉCTRICO - 5.11 %", "62.47 €", "54.79 €",
            "IVA - 21.00 %", "269.63 €", "239.07 €",
            "COSTE ANUAL FACTURA ELÉCTRICA", "1553.35 €", "1377.61 €",
            "", "AHORRO ANUAL", "175.74 €\n11%"
        ), a)
        val b = ComparisonTableModel.pages(F.pageScenarioB()).single().cells
        assertEquals(listOf("175.74 €\n11%", "-62.10 €\n-4%", "0.00 €\n0%"), b.takeLast(3).map { it.text })
        assertEquals(listOf(ComparisonCellStyle.SAVINGS, ComparisonCellStyle.SAVINGS_NEGATIVE, ComparisonCellStyle.SAVINGS), b.takeLast(3).map { it.style })
        val c = ComparisonTableModel.pages(F.pageScenarioC()).single().cells
        assertEquals(listOf("5%", "10.00 €", ""), c.takeLast(3).map { it.text })
        assertEquals(List(3) { ComparisonCellStyle.SAVINGS }, c.takeLast(3).map { it.style })
        val d = ComparisonTableModel.pages(F.pageScenarioD()).single().cells
        val extraRow = d.first { it.text == "SERVICIOS EXTRA" }.row
        assertEquals(listOf("SERVICIOS EXTRA", "0.00 €", "0.00 €", "12 €", "12,5 €", "12. €"), d.filter { it.row == extraRow }.map { it.text })
        val q3 = ComparisonTableModel.pages(F.pageScenarioShortItems()).single().cells.map { it.text }
        assertEquals(listOf("P2 15.000 kW", "0.000000 €/kW año", "0.000000"), q3.subList(q3.indexOf("P2 15.000 kW"), q3.indexOf("P2 15.000 kW") + 3))
        assertEquals(listOf("P1 1000 kWh", "0.200000 €/kWh", "0.000000"), q3.subList(q3.indexOf("P1 1000 kWh"), q3.indexOf("P1 1000 kWh") + 3))
        val h = ComparisonTableModel.pages(F.pageScenarioH()).single().cells.map { it.text }
        assertEquals("TOTAL ENERGÍA ANUAL\nCONSUMIDA\n\n23303 kWh", h.first { it.startsWith("TOTAL ENERGÍA") })
        assertEquals(6, h.count { Regex("^P\\d \\d+\\.\\d{3} kW$").matches(it) })
    }
}
