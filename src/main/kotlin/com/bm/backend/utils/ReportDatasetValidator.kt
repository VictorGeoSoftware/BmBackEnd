package com.bm.backend.utils

import com.bm.backend.models.ComparatorReportColumn
import com.bm.backend.models.ComparatorReportPdfRequest
import com.bm.backend.models.ComparatorReportProposal
import com.bm.backend.models.ConsumptionReportResponse
import com.bm.backend.models.ReportDatasetRejection
import kotlin.math.abs
import kotlin.math.truncate

/**
 * Pure, total checks of a client-built comparison against the owned study's annual result.
 * Nothing is recalculated or rewritten; the first failing check (identity, consistency, offers, amounts) wins.
 */
object ReportDatasetValidator {
    private const val MISSING_MARK = "--"
    private const val CENT_TOLERANCE = 0.005 + 1e-9
    private const val POWER_TOLERANCE = 1e-9
    private val PERIODS = listOf("P1", "P2", "P3", "P4", "P5", "P6")
    private val AMOUNT = Regex("^-?\\d+\\.\\d{2}$")
    /** App input format of the broker's fixed amount (user decision F1, 2026-10-29); kept verbatim. */
    private val EXTRA_SERVICES = Regex("^\\d*([.,]\\d{0,2})?$")
    private val PERCENT = Regex("^(\\d+\\.\\d{2}) %$")

    fun validate(comparison: ComparatorReportPdfRequest, report: ConsumptionReportResponse): ReportDatasetRejection? =
        identity(comparison) ?: consistency(comparison, report) ?: offers(comparison, report) ?: amounts(comparison, report)

    private fun missing(value: String): Boolean = value.trim().let { it.isEmpty() || it == MISSING_MARK }

    private fun identity(c: ComparatorReportPdfRequest): ReportDatasetRejection? = when {
        missing(c.supplyHolder) -> ReportDatasetRejection.HOLDER_MISSING
        missing(c.supplyAddress) -> ReportDatasetRejection.ADDRESS_MISSING
        else -> null
    }

    private fun consistency(c: ComparatorReportPdfRequest, report: ConsumptionReportResponse): ReportDatasetRejection? {
        val d = report.consumptionData
        if (!CupsMatch.same(d.cups, c.cups)) return ReportDatasetRejection.CUPS_MISMATCH
        if (c.tariffName.trim() != d.feeType.trim()) return ReportDatasetRejection.TARIFF_MISMATCH
        val powers = listOf(d.subscribedPowerP1, d.subscribedPowerP2, d.subscribedPowerP3,
            d.subscribedPowerP4, d.subscribedPowerP5, d.subscribedPowerP6)
        val energies = listOf(d.annualConsumptionP1, d.annualConsumptionP2, d.annualConsumptionP3,
            d.annualConsumptionP4, d.annualConsumptionP5, d.annualConsumptionP6)
        val powerOk = rowsMatch(c.powerTermRows.map { it.period to it.value }) { i, v -> abs(v - powers[i]) <= POWER_TOLERANCE }
        val energyOk = rowsMatch(c.energyConsumedRows.map { it.period to it.value.toDouble() }) { i, v -> v == truncate(energies[i]) }
        return if (powerOk && energyOk) null else ReportDatasetRejection.CONSUMPTION_MISMATCH
    }

    private fun rowsMatch(rows: List<Pair<String, Double>>, equal: (Int, Double) -> Boolean): Boolean {
        if (rows.map { it.first }.toSet().size != rows.size) return false
        return rows.all { (period, value) ->
            val index = PERIODS.indexOf(period)
            index >= 0 && value.isFinite() && equal(index, value)
        }
    }

    private fun offers(c: ComparatorReportPdfRequest, report: ConsumptionReportResponse): ReportDatasetRejection? {
        if (c.proposals.isEmpty()) return ReportDatasetRejection.NO_OFFERS
        val titles = c.proposals.map { it.title }
        if (titles.any { it.isBlank() } || titles.toSet().size != titles.size) return ReportDatasetRejection.DUPLICATE_OFFER
        val current = report.proposals.associateBy { it.proposalTitle }
        if (titles.any { it !in current }) return ReportDatasetRejection.UNKNOWN_OFFER
        val stale = c.proposals.any { offer ->
            val raw = current.getValue(offer.title)
            drifted(offer.annualPowerTermCost, raw.annualPowerTermCost) ||
                drifted(offer.annualEnergyCost, raw.annualEnergyCost) ||
                drifted(offer.electricalTax, raw.electricalTax)
        }
        return if (stale) ReportDatasetRejection.STALE_OFFER else null
    }

    /** Only well-formed amounts are compared here; malformed ones are reported as invalid_amount later. */
    private fun drifted(amount: String, raw: Double): Boolean {
        val value = amount.takeIf { AMOUNT.matches(it) }?.toDouble() ?: return false
        return !(abs(value - raw) <= CENT_TOLERANCE)
    }

    private fun amounts(c: ComparatorReportPdfRequest, report: ConsumptionReportResponse): ReportDatasetRejection? {
        val customer = c.customerConditions
        if (columnAmounts(customer).any { it.isBlank() }) return ReportDatasetRejection.CURRENT_CONDITIONS_MISSING
        val customerComputed = listOf(customer.annualPowerTermCost, customer.annualEnergyCost, customer.electricalTax,
            customer.iva, customer.totalAnnualPrice)
        val customerOk = columnValid(customerComputed, customer.extraServices, customer.powerTermItems + customer.consumedEnergyItems)
        val offersOk = c.proposals.all { offerValid(it) }
        if (!customerOk || !offersOk) return ReportDatasetRejection.INVALID_AMOUNT
        val iva = PERCENT.matchEntire(c.iva)?.groupValues?.get(1)?.toDouble()
        val electric = PERCENT.matchEntire(c.impuestoElectrico)?.groupValues?.get(1)?.toDouble()
        if (iva == null || electric == null) return ReportDatasetRejection.INVALID_AMOUNT
        val taxesOk = abs(iva - report.iva) <= CENT_TOLERANCE && abs(electric - report.impuestoElectrico) <= CENT_TOLERANCE
        return if (taxesOk) null else ReportDatasetRejection.TAX_MISMATCH
    }

    private fun columnAmounts(col: ComparatorReportColumn): List<String> = listOf(col.annualPowerTermCost,
        col.annualEnergyCost, col.extraServices, col.electricalTax, col.iva, col.totalAnnualPrice)

    private fun offerValid(o: ComparatorReportProposal): Boolean {
        val amounts = listOf(o.annualPowerTermCost, o.annualEnergyCost, o.electricalTax, o.iva, o.totalAnnualPrice)
        val differenceOk = o.annualPriceDifference == null || AMOUNT.matches(o.annualPriceDifference)
        return differenceOk && columnValid(amounts, o.extraServices, o.powerTermItems + o.consumedEnergyItems)
    }

    /** Computed amounts are strict 2-dp; the fixed amount accepts the app input format (non-negative, has a digit). */
    private fun columnValid(computed: List<String>, extraServices: String, items: List<Double>): Boolean =
        computed.all { AMOUNT.matches(it) } && extraServicesValid(extraServices) && items.all { it.isFinite() }

    private fun extraServicesValid(value: String): Boolean =
        EXTRA_SERVICES.matches(value) && value.any { it.isDigit() }
}
