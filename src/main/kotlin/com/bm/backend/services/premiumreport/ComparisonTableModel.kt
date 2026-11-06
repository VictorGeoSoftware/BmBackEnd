package com.bm.backend.services.premiumreport

import com.bm.backend.models.ComparatorReportColumn
import com.bm.backend.models.ComparatorReportPdfRequest
import com.bm.backend.models.ComparatorReportProposal
import com.bm.backend.services.ComparatorReportPdfTexts
import java.util.Locale

/**
 * Pure model of the Premium economic-offer page (spec report-comparison-page, truth table B0–AC). It reproduces
 * the Basic comparator's cell strings with Basic's own rules (copied, not shared, so Basic stays untouched):
 * 7 offers per page, `%.6f`/`%.3f` Locale.US, `" €"` suffix, labels with the tax reference. Amounts are client
 * strings and are never recalculated or reformatted; [com.bm.backend.services.premiumreport.PremiumReportFormat]
 * is deliberately not used here.
 */
object ComparisonTableModel {
    const val OFFERS_PER_PAGE = 7

    fun pages(c: ComparatorReportPdfRequest): List<ComparisonPage> =
        c.proposals.chunked(OFFERS_PER_PAGE).ifEmpty { listOf(emptyList()) }.map { page(c, it) }

    fun withCurrency(value: String): String {
        if (value.isBlank() || value == "-") return value
        return if (value.endsWith("€") || value.endsWith("%")) value else "$value €"
    }

    fun price(value: Double): String = String.format(Locale.US, "%.6f", value)

    fun power(value: Double): String = String.format(Locale.US, "%.3f", value)

    fun labelWithReference(label: String, reference: String): String =
        if (reference.isBlank() || reference == "-") label else "$label - $reference"

    fun savingsText(offer: ComparatorReportProposal): String {
        val difference = withCurrency(offer.annualPriceDifference.orEmpty())
        val percentage = offer.annualSavingsPercentage?.let { "$it%" }.orEmpty()
        return listOf(difference, percentage).filter { it.isNotBlank() }.joinToString("\n")
    }

    fun isNegative(difference: String?): Boolean = !difference.isNullOrBlank() && difference.trim().startsWith("-")

    private fun column(o: ComparatorReportProposal) = ComparatorReportColumn(
        o.title, o.powerTermItems, o.annualPowerTermCost, o.consumedEnergyItems, o.annualEnergyCost,
        o.extraServices, o.electricalTax, o.iva, o.totalAnnualPrice
    )

    private fun page(c: ComparatorReportPdfRequest, offers: List<ComparatorReportProposal>): ComparisonPage {
        val columns = listOf(c.customerConditions) + offers.map(::column)
        val cells = mutableListOf<ComparisonCell>()
        var row = 0

        cells += ComparisonCell(row, 0, c.tariffName, ComparisonCellStyle.HEADER_HIGHLIGHT)
        cells += ComparisonCell(row, 1, ComparatorReportPdfTexts.ANNUAL_CONSUMPTION_COLUMN, ComparisonCellStyle.HEADER_HIGHLIGHT)
        columns.forEachIndexed { i, col ->
            if (i == 0) cells += ComparisonCell(row, 2, ComparatorReportPdfTexts.CURRENT_CONDITIONS_COLUMN, ComparisonCellStyle.HEADER_CURRENT)
            else cells += ComparisonCell(row, 2 + i, col.title, ComparisonCellStyle.HEADER_OFFER)
        }
        row++

        c.powerTermRows.forEachIndexed { idx, r ->
            if (idx == 0) cells += ComparisonCell(row, 0, ComparatorReportPdfTexts.POWER_TERM_SIDE_TITLE,
                ComparisonCellStyle.SIDE_TITLE, rowSpan = c.powerTermRows.size)
            cells += ComparisonCell(row, 1, "${r.period} ${power(r.value)} kW", ComparisonCellStyle.VALUE)
            columns.forEachIndexed { i, col ->
                val v = price(col.powerTermItems.getOrElse(idx) { 0.0 })
                cells += ComparisonCell(row, 2 + i, if (i == 0) "$v €/kW año" else v, ComparisonCellStyle.VALUE)
            }
            row++
        }
        row = total(cells, row, ComparatorReportPdfTexts.POWER_TERM_ANNUAL_COST_LABEL, columns.map { it.annualPowerTermCost })

        val totalEnergy = c.energyConsumedRows.sumOf { it.value }
        c.energyConsumedRows.forEachIndexed { idx, r ->
            if (idx == 0) cells += ComparisonCell(row, 0,
                "${ComparatorReportPdfTexts.ENERGY_CONSUMED_SIDE_TITLE}\n\n$totalEnergy kWh",
                ComparisonCellStyle.SIDE_TITLE_ENERGY, rowSpan = c.energyConsumedRows.size)
            cells += ComparisonCell(row, 1, "${r.period} ${r.value} kWh", ComparisonCellStyle.VALUE)
            columns.forEachIndexed { i, col ->
                val v = price(col.consumedEnergyItems.getOrElse(idx) { 0.0 })
                cells += ComparisonCell(row, 2 + i, if (i == 0) "$v €/kWh" else v, ComparisonCellStyle.VALUE)
            }
            row++
        }
        row = total(cells, row, ComparatorReportPdfTexts.ENERGY_TERM_ANNUAL_COST_LABEL, columns.map { it.annualEnergyCost })

        row = meta(cells, row, ComparatorReportPdfTexts.EXTRA_SERVICES_LABEL, columns.map { it.extraServices })
        row = meta(cells, row, labelWithReference(ComparatorReportPdfTexts.ELECTRIC_TAX_LABEL, c.impuestoElectrico), columns.map { it.electricalTax })
        row = meta(cells, row, labelWithReference(ComparatorReportPdfTexts.IVA_LABEL, c.iva), columns.map { it.iva })
        row = total(cells, row, ComparatorReportPdfTexts.ANNUAL_INVOICE_COST_LABEL, columns.map { it.totalAnnualPrice })

        cells += ComparisonCell(row, 0, "", ComparisonCellStyle.BLANK, colSpan = 2)
        cells += ComparisonCell(row, 2, ComparatorReportPdfTexts.ANNUAL_SAVINGS_LABEL, ComparisonCellStyle.SAVINGS)
        offers.forEachIndexed { i, o ->
            val style = if (isNegative(o.annualPriceDifference)) ComparisonCellStyle.SAVINGS_NEGATIVE else ComparisonCellStyle.SAVINGS
            cells += ComparisonCell(row, 3 + i, savingsText(o), style)
        }
        return ComparisonPage(offers, 2 + columns.size, cells)
    }

    private fun total(cells: MutableList<ComparisonCell>, row: Int, label: String, values: List<String>): Int {
        cells += ComparisonCell(row, 0, label, ComparisonCellStyle.TOTAL, colSpan = 2)
        values.forEachIndexed { i, v -> cells += ComparisonCell(row, 2 + i, withCurrency(v), ComparisonCellStyle.TOTAL) }
        return row + 1
    }

    private fun meta(cells: MutableList<ComparisonCell>, row: Int, label: String, values: List<String>): Int {
        cells += ComparisonCell(row, 0, label, ComparisonCellStyle.META_LABEL, colSpan = 2)
        values.forEachIndexed { i, v -> cells += ComparisonCell(row, 2 + i, withCurrency(v), ComparisonCellStyle.VALUE) }
        return row + 1
    }
}
