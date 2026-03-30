package com.bm.backend.models

import kotlinx.serialization.Serializable

@Serializable
data class ComparatorReportPdfRequest(
    val title: String = "COMPARATIVO ANUAL DE PRECIOS FIJOS",
    val supplyHolder: String,
    val supplyAddress: String,
    val cups: String,
    val tariffName: String,
    val powerTermRows: List<ComparatorReportPeriodValue>,
    val energyConsumedRows: List<ComparatorReportPeriodIntValue>,
    val iva: String,
    val impuestoElectrico: String,
    val customerConditions: ComparatorReportColumn,
    val proposals: List<ComparatorReportProposal>
)

@Serializable
data class ComparatorReportPeriodValue(
    val period: String,
    val value: Double
)

@Serializable
data class ComparatorReportPeriodIntValue(
    val period: String,
    val value: Int
)

@Serializable
data class ComparatorReportColumn(
    val title: String,
    val powerTermItems: List<Double>,
    val annualPowerTermCost: String,
    val consumedEnergyItems: List<Double>,
    val annualEnergyCost: String,
    val extraServices: String,
    val electricalTax: String,
    val iva: String,
    val totalAnnualPrice: String
)

@Serializable
data class ComparatorReportProposal(
    val title: String,
    val powerTermItems: List<Double>,
    val annualPowerTermCost: String,
    val consumedEnergyItems: List<Double>,
    val annualEnergyCost: String,
    val extraServices: String,
    val electricalTax: String,
    val iva: String,
    val totalAnnualPrice: String,
    val annualPriceDifference: String?,
    val annualSavingsPercentage: Int?
)
