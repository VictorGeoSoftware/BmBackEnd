package com.bm.backend.models

import kotlinx.serialization.Serializable

// Docling API Response Models
@Serializable
data class DoclingApiResponse(
    val extractedData: DoclingExtractedData,
    val fileName: String,
    val success: Boolean
)

@Serializable
data class DoclingExtractedData(
    val cups_code: String,
    val customer_details: CustomerDetails,
    val customer_id: CustomerId
)

@Serializable
data class CustomerDetails(
    val address: String,
    val name: String? = null
)

@Serializable
data class CustomerId(
    val context_text: String,
    val original_format: String,
    val type: String,
    val value: String
)

// N8N Webhook Response Models
@Serializable
data class N8nWebhookResponse(
    val success: Boolean,
    val data: N8nConsumptionData,
    val processedAt: String
)

@Serializable
data class N8nConsumptionData(
    val cups: String,
    val tarifa: String,
    val tarifaValue: String,
    val annualConsumption: String,
    val annualConsumptionP1: String,
    val annualConsumptionP2: String,
    val annualConsumptionP3: String,
    val annualConsumptionP4: String,
    val annualConsumptionP5: String,
    val annualConsumptionP6: String,
    val subscribedPowerP1: String,
    val subscribedPowerP2: String,
    val subscribedPowerP3: String,
    val subscribedPowerP4: String,
    val subscribedPowerP5: String,
    val subscribedPowerP6: String,
    val feeType: String,
    val fileName: String
)

// Consolidated Response Model
@Serializable
data class ConsumptionReportResponse(
    val success: Boolean,
    val userData: DoclingExtractedData,
    val consumptionData: CleanedConsumptionData,
    val proposals: List<ProposalPriceModel>
)

@Serializable
data class CleanedConsumptionData(
    val cups: String,
    val tarifa: String,
    val tarifaValue: String,
    val annualConsumption: Double,
    val annualConsumptionP1: Double,
    val annualConsumptionP2: Double,
    val annualConsumptionP3: Double,
    val annualConsumptionP4: Double,
    val annualConsumptionP5: Double,
    val annualConsumptionP6: Double,
    val subscribedPowerP1: Double,
    val subscribedPowerP2: Double,
    val subscribedPowerP3: Double,
    val subscribedPowerP4: Double,
    val subscribedPowerP5: Double,
    val subscribedPowerP6: Double,
    val feeType: String,
    val fileName: String,
    val processedAt: String
)

// Proposal Price Model
@Serializable
data class ProposalPriceModel(
    val proposalTitle: String,
    val powerTermItems: List<Double>,
    val annualPowerTermCost: Double,
    val consumedEnergyItems: List<Double>,
    val annualEnergyCost: Double,
    val extraServices: Double,
    val iva: Double,
    val electricalTax: Double,
    val totalAnnualPrice: Double
)
