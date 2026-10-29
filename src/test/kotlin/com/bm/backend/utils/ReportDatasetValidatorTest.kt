package com.bm.backend.utils

import com.bm.backend.models.ComparatorReportPdfRequest
import com.bm.backend.models.ComparatorReportPeriodValue
import com.bm.backend.models.ReportDatasetRejection
import com.bm.backend.models.ReportDatasetRejection.ADDRESS_MISSING
import com.bm.backend.models.ReportDatasetRejection.CONSUMPTION_MISMATCH
import com.bm.backend.models.ReportDatasetRejection.CUPS_MISMATCH
import com.bm.backend.models.ReportDatasetRejection.CURRENT_CONDITIONS_MISSING
import com.bm.backend.models.ReportDatasetRejection.DUPLICATE_OFFER
import com.bm.backend.models.ReportDatasetRejection.HOLDER_MISSING
import com.bm.backend.models.ReportDatasetRejection.INVALID_AMOUNT
import com.bm.backend.models.ReportDatasetRejection.NO_OFFERS
import com.bm.backend.models.ReportDatasetRejection.STALE_OFFER
import com.bm.backend.models.ReportDatasetRejection.TARIFF_MISMATCH
import com.bm.backend.models.ReportDatasetRejection.TAX_MISMATCH
import com.bm.backend.models.ReportDatasetRejection.UNKNOWN_OFFER
import com.bm.backend.testing.ReportComparisonFixtures
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReportDatasetValidatorTest {
    private val report = ReportComparisonFixtures.study().report
    private val base = ReportComparisonFixtures.comparison(report, "ES0000000000000000AA")

    private fun check(c: ComparatorReportPdfRequest) = ReportDatasetValidator.validate(c, report)

    private fun offer0(transform: (com.bm.backend.models.ComparatorReportProposal) -> com.bm.backend.models.ComparatorReportProposal) =
        base.copy(proposals = listOf(transform(base.proposals[0])) + base.proposals.drop(1))

    private fun assertReason(expected: ReportDatasetRejection, c: ComparatorReportPdfRequest) = assertEquals(expected, check(c))

    @Test
    fun `valid client comparison passes`() {
        assertNull(check(base))
        assertNull(check(base.copy(cups = " ${report.consumptionData.cups.lowercase()} ")))
    }

    @Test
    fun `C identity must be present`() {
        listOf("", "  ", "--", " -- ").forEach {
            assertReason(HOLDER_MISSING, base.copy(supplyHolder = it))
            assertReason(ADDRESS_MISSING, base.copy(supplyAddress = it))
        }
        assertReason(HOLDER_MISSING, base.copy(supplyHolder = "--", supplyAddress = "--"))
    }

    @Test
    fun `D study consistency`() {
        assertReason(CUPS_MISMATCH, base.copy(cups = "ES0000000000000000AB"))
        assertReason(CUPS_MISMATCH, base.copy(cups = ""))
        assertReason(TARIFF_MISMATCH, base.copy(tariffName = "indexed"))
        assertNull(check(base.copy(tariffName = " fixed ")))
        val rows = base.powerTermRows
        assertReason(CONSUMPTION_MISMATCH, base.copy(powerTermRows = listOf(rows[0].copy(value = 14.9)) + rows.drop(1)))
        assertReason(CONSUMPTION_MISMATCH, base.copy(powerTermRows = rows + ComparatorReportPeriodValue("P7", 15.0)))
        assertReason(CONSUMPTION_MISMATCH, base.copy(powerTermRows = rows + rows[0]))
        assertReason(CONSUMPTION_MISMATCH, base.copy(powerTermRows = listOf(rows[0].copy(value = Double.NaN))))
        val energy = base.energyConsumedRows
        assertReason(CONSUMPTION_MISMATCH, base.copy(energyConsumedRows = listOf(energy[0].copy(value = energy[0].value + 1))))
        assertReason(CONSUMPTION_MISMATCH, base.copy(energyConsumedRows = listOf(energy[0].copy(period = "p1"))))
        assertReason(CUPS_MISMATCH, base.copy(cups = "X", tariffName = "indexed"))
    }

    @Test
    fun `E offers`() {
        assertReason(NO_OFFERS, base.copy(proposals = emptyList()))
        assertReason(DUPLICATE_OFFER, base.copy(proposals = base.proposals + base.proposals[0]))
        assertReason(DUPLICATE_OFFER, offer0 { it.copy(title = " ") })
        assertReason(UNKNOWN_OFFER, offer0 { it.copy(title = "Oferta inexistente") })
        val raw = report.proposals.first { it.proposalTitle == base.proposals[0].title }
        assertReason(STALE_OFFER, offer0 { it.copy(annualEnergyCost = ReportComparisonFixtures.money(raw.annualEnergyCost + 0.02)) })
        assertReason(STALE_OFFER, offer0 { it.copy(annualPowerTermCost = ReportComparisonFixtures.money(raw.annualPowerTermCost - 0.02)) })
        assertReason(STALE_OFFER, offer0 { it.copy(electricalTax = ReportComparisonFixtures.money(raw.electricalTax + 1.0)) })
        assertNull(check(offer0 { it.copy(annualEnergyCost = String.format(java.util.Locale.ROOT, "%.2f", raw.annualEnergyCost)) }))
        // iva/total/items are not cross-checked (they include the broker's fixed amount)
        assertNull(check(offer0 { it.copy(iva = "1.00", totalAnnualPrice = "2.00", powerTermItems = listOf(9.0)) }))
    }

    @Test
    fun `E offer 0_004 from the raw value (rounding) is accepted, 0_02 is stale`() {
        val title = base.proposals[0].title
        val rawEnergy = 1234.564
        val study = report.copy(proposals = report.proposals.map {
            if (it.proposalTitle == title) it.copy(annualEnergyCost = rawEnergy) else it
        })
        assertNull(ReportDatasetValidator.validate(offer0 { it.copy(annualEnergyCost = "1234.56") }, study))
        assertNull(ReportDatasetValidator.validate(offer0 { it.copy(annualEnergyCost = "1234.56") }, study.copy(proposals =
            study.proposals.map { if (it.proposalTitle == title) it.copy(annualEnergyCost = 1234.556) else it })))
        assertEquals(STALE_OFFER, ReportDatasetValidator.validate(offer0 { it.copy(annualEnergyCost = "1234.58") }, study))
    }

    @Test
    fun `F1 extraServices accepts the app input format verbatim`() {
        listOf("12", "12,5", "12,50", "12.", "0.00", ",5", "12.5").forEach { ok ->
            assertNull(check(offer0 { it.copy(extraServices = ok) }), ok)
            assertNull(check(base.copy(customerConditions = base.customerConditions.copy(extraServices = ok))), ok)
        }
        listOf("-1", "1.234", "abc", ".", ",", "-12.00", "NaN", " 12.00", "12,00 ").forEach { bad ->
            assertReason(INVALID_AMOUNT, offer0 { it.copy(extraServices = bad) })
            assertReason(INVALID_AMOUNT, base.copy(customerConditions = base.customerConditions.copy(extraServices = bad)))
        }
    }

    @Test
    fun `F amounts and taxes`() {
        listOf("12,00", "12.0", "NaN", "-", "", " 12.00").forEach { bad ->
            assertReason(INVALID_AMOUNT, offer0 { it.copy(totalAnnualPrice = bad) })
        }
        assertReason(INVALID_AMOUNT, offer0 { it.copy(iva = "12,00") })
        assertReason(INVALID_AMOUNT, offer0 { it.copy(annualEnergyCost = "NaN") })
        assertReason(INVALID_AMOUNT, offer0 { it.copy(annualPriceDifference = "1.5") })
        assertNull(check(offer0 { it.copy(annualPriceDifference = null, annualSavingsPercentage = null) }))
        assertNull(check(offer0 { it.copy(annualPriceDifference = "-3.10", annualSavingsPercentage = -2) }))
        assertReason(INVALID_AMOUNT, offer0 { it.copy(consumedEnergyItems = listOf(Double.POSITIVE_INFINITY)) })
        assertReason(INVALID_AMOUNT, base.copy(customerConditions = base.customerConditions.copy(totalAnnualPrice = "9000")))
        assertReason(INVALID_AMOUNT, base.copy(customerConditions = base.customerConditions.copy(powerTermItems = listOf(Double.NaN))))
        assertReason(INVALID_AMOUNT, base.copy(customerConditions = base.customerConditions.copy(extraServices = "-1.00")))
        assertReason(INVALID_AMOUNT, base.copy(customerConditions = base.customerConditions.copy(electricalTax = "12.0")))
        assertReason(INVALID_AMOUNT, base.copy(customerConditions = base.customerConditions.copy(iva = "12,00")))
        assertReason(INVALID_AMOUNT, offer0 { it.copy(annualPowerTermCost = "12,00") })
        val c = base.customerConditions
        assertReason(CURRENT_CONDITIONS_MISSING, base.copy(customerConditions = c.copy(annualPowerTermCost = "", annualEnergyCost = "",
            extraServices = "", electricalTax = "", iva = "", totalAnnualPrice = "")))
        assertReason(CURRENT_CONDITIONS_MISSING, base.copy(customerConditions = c.copy(totalAnnualPrice = " ")))
        assertReason(TAX_MISMATCH, base.copy(iva = "10.00 %"))
        assertReason(TAX_MISMATCH, base.copy(impuestoElectrico = "5.20 %"))
        assertReason(INVALID_AMOUNT, base.copy(iva = "21,00 %"))
        assertReason(INVALID_AMOUNT, base.copy(iva = "21.00%"))
    }
}
