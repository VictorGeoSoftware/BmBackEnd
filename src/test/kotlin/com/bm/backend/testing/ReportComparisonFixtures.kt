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

    // ---- Economic comparison page scenarios (spec report-comparison-page A–H, Q3); synthetic only ----

    /** Same shape and values as `report/comparison_request.json`. */
    fun pageRequest(): ComparatorReportPdfRequest = ComparatorReportPdfRequest(
        supplyHolder = "Cliente Ficticio SL",
        supplyAddress = "Calle Falsa 1",
        cups = "ES0000000000000000AA",
        tariffName = "fixed",
        powerTermRows = listOf(ComparatorReportPeriodValue("P1", 15.0), ComparatorReportPeriodValue("P2", 15.0)),
        energyConsumedRows = listOf(ComparatorReportPeriodIntValue("P1", 1000), ComparatorReportPeriodIntValue("P2", 2000)),
        iva = "21.00 %",
        impuestoElectrico = "5.11 %",
        customerConditions = ComparatorReportColumn(
            title = "Condiciones actuales", powerTermItems = listOf(0.1, 0.05), annualPowerTermCost = "821.25",
            consumedEnergyItems = listOf(0.2, 0.1), annualEnergyCost = "400.00", extraServices = "0.00",
            electricalTax = "62.47", iva = "269.63", totalAnnualPrice = "1553.35"
        ),
        proposals = listOf(pageOffer("Oferta Ficticia A", "175.74", 11, extra = "12.00"))
    )

    fun pageOffer(
        title: String,
        difference: String?,
        percentage: Int?,
        extra: String = "0.00",
        power: List<Double> = listOf(0.09, 0.04),
        energy: List<Double> = listOf(0.18, 0.09)
    ): ComparatorReportProposal = ComparatorReportProposal(
        title = title, powerTermItems = power, annualPowerTermCost = "711.75", consumedEnergyItems = energy,
        annualEnergyCost = "360.00", extraServices = extra, electricalTax = "54.79", iva = "239.07",
        totalAnnualPrice = "1377.61", annualPriceDifference = difference, annualSavingsPercentage = percentage
    )

    fun pageScenarioB(): ComparatorReportPdfRequest = pageRequest().copy(proposals = listOf(
        pageOffer("Oferta Ahorro", "175.74", 11),
        pageOffer("Oferta Cara", "-62.10", -4),
        pageOffer("Oferta Igual", "0.00", 0)
    ))

    fun pageScenarioC(): ComparatorReportPdfRequest = pageRequest().copy(proposals = listOf(
        pageOffer("Oferta Sin Importe", null, 5),
        pageOffer("Oferta Sin Porcentaje", "10.00", null),
        pageOffer("Oferta Sin Ahorro", null, null)
    ))

    fun pageScenarioD(): ComparatorReportPdfRequest = pageRequest().copy(
        customerConditions = pageRequest().customerConditions.copy(extraServices = "0.00"),
        proposals = listOf(
            pageOffer("Oferta Cero", "1.00", 1, extra = "0.00"),
            pageOffer("Oferta Entera", "2.00", 1, extra = "12"),
            pageOffer("Oferta Coma", "3.00", 1, extra = "12,5"),
            pageOffer("Oferta Punto", "4.00", 1, extra = "12.")
        )
    )

    fun pageScenarioOffers(count: Int): ComparatorReportPdfRequest = pageRequest().copy(
        proposals = (1..count).map { pageOffer("Oferta Ficticia $it", "${100 + it}.00", it) }
    )

    fun pageScenarioG(): ComparatorReportPdfRequest = pageRequest().copy(
        supplyHolder = "Comunidad de Propietarios del Edificio Ficticio de la Avenida Imaginaria",
        tariffName = "Tarifa de acceso ficticia con nombre muy largo 2.0TD",
        proposals = listOf(
            pageOffer("Oferta Ficticia con un título comercial muy largo que debe ajustarse en varias líneas", "175.74", 11),
            pageOffer("Oferta Corta", "-1.00", 0)
        )
    )

    fun pageScenarioH(): ComparatorReportPdfRequest {
        val p = (1..6).map { "P$it" }
        val prices = listOf(0.1, 0.09, 0.08, 0.07, 0.06, 0.05)
        return pageRequest().copy(
            tariffName = "3.0TD",
            powerTermRows = p.mapIndexed { i, s -> ComparatorReportPeriodValue(s, listOf(15.1, 15.1, 20.0, 20.0, 34.0, 34.5)[i]) },
            energyConsumedRows = p.mapIndexed { i, s -> ComparatorReportPeriodIntValue(s, listOf(1200, 3400, 2100, 900, 700, 15003)[i]) },
            customerConditions = pageRequest().customerConditions.copy(powerTermItems = prices, consumedEnergyItems = prices.map { it * 2 }),
            proposals = listOf(
                pageOffer("Oferta Seis A", "321.00", 7, power = prices.map { it * 0.9 }, energy = prices.map { it * 1.8 }),
                pageOffer("Oferta Seis B", "-5.25", -1, power = prices.map { it * 0.8 }, energy = prices.map { it * 1.7 })
            )
        )
    }

    /** Q3 (a): item lists shorter than the rows; Basic prints 0.000000 for the missing index. */
    fun pageScenarioShortItems(): ComparatorReportPdfRequest = pageRequest().copy(
        customerConditions = pageRequest().customerConditions.copy(powerTermItems = listOf(0.1), consumedEnergyItems = listOf(0.2)),
        proposals = listOf(pageOffer("Oferta Corta", "175.74", 11, power = listOf(0.09), energy = emptyList()))
    )
}
