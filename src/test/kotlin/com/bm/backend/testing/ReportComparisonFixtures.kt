package com.bm.backend.testing

import com.bm.backend.models.ComparatorReportColumn
import com.bm.backend.models.ComparatorReportPdfRequest
import com.bm.backend.models.ComparatorReportPeriodIntValue
import com.bm.backend.models.ComparatorReportPeriodValue
import com.bm.backend.models.ComparatorReportProposal
import com.bm.backend.models.ConsumptionReportResponse
import com.bm.backend.models.FilteredExtractedTables
import com.bm.backend.models.FilteredPriceTableResponse
import com.bm.backend.models.FilteredPriceTableResult
import com.bm.backend.models.FilteredTablaPrecioClasicaBase
import com.bm.backend.models.FilteredTablaPrecioClasicaUnica
import com.bm.backend.models.FilteredTablaPrecioPotencia
import com.bm.backend.models.FilteredTerminoDeEnergia
import com.bm.backend.models.FilteredTerminoDePotencia
import com.bm.backend.models.ProposalPriceModel
import com.bm.backend.models.ConsumptionStudyResult
import com.bm.backend.services.ExternalApiService
import com.bm.backend.services.PriceTableService
import com.bm.backend.services.UserConsumptionService
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.truncate

/** Synthetic price tables and a comparison built the way the clients build it (2 dp half-up, fixed amount). */
object ReportComparisonFixtures {
    const val IVA = 21.0
    const val ELECTRIC_TAX = 5.11269632

    private fun result(name: String, power: Double, energy: Double): FilteredPriceTableResult {
        val powerRow = PriceTableFixtures.tarifa(name = "3.0TD", p1 = power, p2 = power * 0.8, p3 = power * 0.6,
            p4 = power * 0.5, p5 = power * 0.4, p6 = power * 0.3)
        val energyRow = PriceTableFixtures.tarifa(name = "3.0TD", p1 = energy, p2 = energy * 0.9, p3 = energy * 0.8,
            p4 = energy * 0.7, p5 = energy * 0.6, p6 = energy * 0.5)
        return FilteredPriceTableResult(
            fileName = "$name.pdf",
            extracted_tables = FilteredExtractedTables(
                companyName = name,
                termino_de_potencia = FilteredTerminoDePotencia(
                    titulo = "Termino de potencia - Oferta $name",
                    tabla_precio_potencia = FilteredTablaPrecioPotencia(titulo = "Potencia", tarifa = powerRow)
                ),
                termino_de_energia = FilteredTerminoDeEnergia(
                    titulo = "Termino de energia - Oferta $name",
                    tabla_precio_clasica_base = FilteredTablaPrecioClasicaBase(titulo = "Base", tarifa = energyRow),
                    tabla_precio_clasica_unica = FilteredTablaPrecioClasicaUnica(titulo = "Única", tarifa = energyRow)
                )
            )
        )
    }

    fun prices(): FilteredPriceTableResponse = FilteredPriceTableResponse(
        success = true,
        results = listOf(result("Ficticia Uno", 0.08, 0.15), result("Ficticia Dos", 0.07, 0.17)),
        iva = IVA,
        impuestoElectrico = ELECTRIC_TAX
    )

    /** A completed study from the synthetic `snapshot_v1.json` webhook and two synthetic offers. */
    fun study(cups: String = "ES0000000000000000AA0F"): ConsumptionStudyResult = runBlocking {
        val externalApiService = mockk<ExternalApiService>()
        coEvery { externalApiService.processWithN8nWebhook(any()) } returns N8nFixtures.response("snapshot_v1.json")
        val priceTableService = mockk<PriceTableService>()
        every { priceTableService.getFilteredPriceTableResults(any()) } returns prices()
        UserConsumptionService(InMemoryUserConsumptionRepository(), externalApiService, priceTableService)
            .processConsumptionReportFromCups(cups)
    }

    fun money(value: Double): String = BigDecimal(value.toString()).setScale(2, RoundingMode.HALF_UP).toPlainString()

    private fun percent(value: Double): String = "${money(value)} %"

    private fun offer(p: ProposalPriceModel, fixedAmount: Double, current: Double): ComparatorReportProposal {
        val iva = p.iva + fixedAmount * IVA / 100.0
        val total = p.totalAnnualPrice + fixedAmount + fixedAmount * IVA / 100.0
        return ComparatorReportProposal(
            title = p.proposalTitle,
            powerTermItems = p.powerTermItems,
            annualPowerTermCost = money(p.annualPowerTermCost),
            consumedEnergyItems = p.consumedEnergyItems,
            annualEnergyCost = money(p.annualEnergyCost),
            extraServices = money(fixedAmount),
            electricalTax = money(p.electricalTax),
            iva = money(iva),
            totalAnnualPrice = money(total),
            annualPriceDifference = money(current - total),
            annualSavingsPercentage = ((current - total) / current * 100).toInt()
        )
    }

    /** Offers in reverse backend order; the first given offer carries a 12.00 fixed amount. */
    fun comparison(report: ConsumptionReportResponse, cups: String): ComparatorReportPdfRequest {
        val d = report.consumptionData
        val powers = listOf(d.subscribedPowerP1, d.subscribedPowerP2, d.subscribedPowerP3,
            d.subscribedPowerP4, d.subscribedPowerP5, d.subscribedPowerP6)
        val energies = listOf(d.annualConsumptionP1, d.annualConsumptionP2, d.annualConsumptionP3,
            d.annualConsumptionP4, d.annualConsumptionP5, d.annualConsumptionP6)
        val current = 9000.0
        val offers = report.proposals.reversed()
        return ComparatorReportPdfRequest(
            supplyHolder = "Cliente Ficticio SL",
            supplyAddress = "Calle Falsa 1",
            cups = cups,
            tariffName = d.feeType,
            powerTermRows = powers.mapIndexedNotNull { i, v -> if (v > 0) ComparatorReportPeriodValue("P${i + 1}", v) else null },
            energyConsumedRows = energies.mapIndexed { i, v -> ComparatorReportPeriodIntValue("P${i + 1}", truncate(v).toInt()) },
            iva = percent(report.iva),
            impuestoElectrico = percent(report.impuestoElectrico),
            customerConditions = ComparatorReportColumn(
                title = "Condiciones actuales",
                powerTermItems = listOf(0.1, 0.09, 0.08, 0.07, 0.06, 0.05),
                annualPowerTermCost = "2500.00",
                consumedEnergyItems = listOf(0.2, 0.19, 0.18, 0.17, 0.16, 0.15),
                annualEnergyCost = "4500.00",
                extraServices = "0.00",
                electricalTax = "357.89",
                iva = "1620.16",
                totalAnnualPrice = money(current)
            ),
            proposals = offers.mapIndexed { i, p -> offer(p, if (i == 0) 12.0 else 0.0, current) }
        )
    }
}
