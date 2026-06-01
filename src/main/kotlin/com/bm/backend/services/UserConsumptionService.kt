package com.bm.backend.services

import com.bm.backend.models.ConsumptionReportResponse
import com.bm.backend.models.DoclingExtractedData
import com.bm.backend.models.UserConsumption
import com.bm.backend.repositories.ports.UserConsumptionRepositoryPort
import com.bm.backend.utils.calculateProposals
import com.bm.backend.utils.toCleanedData
import java.io.File

class UserConsumptionService(
    private val userConsumptionRepository: UserConsumptionRepositoryPort,
    private val externalApiService: ExternalApiService,
    private val priceTableService: PriceTableService
) {
    
    fun processConsumptionReport(consumptionReport: UserConsumption) {
        // Store the consumption data
        userConsumptionRepository.storeConsumptionData(consumptionReport)
    }
    
    suspend fun processConsumptionReportFromPdf(pdfFile: File): ConsumptionReportResponse {
        // Step 1: Extract data from PDF via Docling API
        val extractedData = runCatching {
            externalApiService.extractDataFromPdf(pdfFile)
        }.getOrElse { e ->
            throw Exception("Failed at Docling API extraction step: ${e.message}", e)
        }

        // Steps 2-6: shared pipeline
        return runConsumptionPipeline(extractedData)
    }

    suspend fun processConsumptionReportFromCups(cupsCode: String): ConsumptionReportResponse {
        val normalizedCupsCode = cupsCode.trim().uppercase()
        val extractedData = DoclingExtractedData(cups_code = normalizedCupsCode)
        return runConsumptionPipeline(extractedData)
    }

    /**
     * Shared post-extraction pipeline:
     * 1. Push extracted data through the n8n webhook to obtain raw consumption data.
     * 2. Clean / normalize the consumption data.
     * 3. Resolve filtered prices for the resulting tariff.
     * 4. Calculate proposals.
     * 5. Build the consolidated response.
     */
    private suspend fun runConsumptionPipeline(
        extractedData: DoclingExtractedData
    ): ConsumptionReportResponse {
        val n8nResponse = runCatching {
            externalApiService.processWithN8nWebhook(extractedData)
        }.getOrElse { e ->
            throw Exception("Failed at N8N webhook processing step: ${e.message}", e)
        }

        val cleanedConsumptionData = n8nResponse.data.toCleanedData(n8nResponse.processedAt)
        val filteredPrices = resolveFilteredPrices(cleanedConsumptionData)
        val proposals = calculateProposals(cleanedConsumptionData, filteredPrices)

        return ConsumptionReportResponse(
            success = true,
            userData = extractedData,
            consumptionData = cleanedConsumptionData,
            proposals = proposals,
            iva = filteredPrices.iva,
            impuestoElectrico = filteredPrices.impuestoElectrico,
        )
    }

    fun refreshProposals(consumptionData: com.bm.backend.models.CleanedConsumptionData): List<com.bm.backend.models.ProposalPriceModel> {
        return resolveProposals(consumptionData)
    }

    fun getCurrentTaxSettings(): com.bm.backend.models.TaxSettingsResponse {
        return priceTableService.getTaxSettings()
    }

    private fun resolveProposals(consumptionData: com.bm.backend.models.CleanedConsumptionData): List<com.bm.backend.models.ProposalPriceModel> {
        val filteredPrices = resolveFilteredPrices(consumptionData)

        return calculateProposals(
            consumptionData,
            filteredPrices
        )
    }

    private fun resolveFilteredPrices(consumptionData: com.bm.backend.models.CleanedConsumptionData): com.bm.backend.models.FilteredPriceTableResponse {
        return runCatching {
            priceTableService.getFilteredPriceTableResults(consumptionData.tarifa)
        }.getOrElse { e ->
            throw Exception("Failed at price table filtering step: ${e.message}", e)
        }
    }

}
