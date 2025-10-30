package com.bm.backend.services

import com.bm.backend.models.ConsumptionReportResponse
import com.bm.backend.models.UserConsumption
import com.bm.backend.repositories.UserConsumptionRepository
import com.bm.backend.utils.toCleanedData
import java.io.File

class UserConsumptionService(
    private val userConsumptionRepository: UserConsumptionRepository,
    private val externalApiService: ExternalApiService,
    private val priceTableService: PriceTableService
) {
    
    fun processConsumptionReport(consumptionReport: UserConsumption) {
        // Store the consumption data
        userConsumptionRepository.storeConsumptionData(consumptionReport)
    }
    
    fun getConsumptionReport(): UserConsumption? {
        return userConsumptionRepository.getConsumptionReport()
    }
    
    suspend fun processConsumptionReportFromPdf(pdfFile: File): ConsumptionReportResponse {
        // Step 1: Extract data from PDF via Docling API
        val doclingData = runCatching {
            externalApiService.extractDataFromPdf(pdfFile)
        }.getOrElse { e ->
            throw Exception("Failed at Docling API extraction step: ${e.message}", e)
        }
        
        // Step 2: Process with N8N webhook
        val n8nResponse = runCatching {
            externalApiService.processWithN8nWebhook(doclingData)
        }.getOrElse { e ->
            throw Exception("Failed at N8N webhook processing step: ${e.message}", e)
        }
        
        // Step 3: Clean the consumption data
        val cleanedConsumptionData = n8nResponse.data.toCleanedData(n8nResponse.processedAt)
        
        // Step 4: Get filtered price table results based on tarifa
        val filteredPrices = runCatching {
            priceTableService.getFilteredPriceTableResults(cleanedConsumptionData.tarifa)
        }.getOrElse { e ->
            throw Exception("Failed at price table filtering step: ${e.message}", e)
        }
        
        // Step 5: Build consolidated response
        return ConsumptionReportResponse(
            success = true,
            doclingData = doclingData,
            consumptionData = cleanedConsumptionData,
            filteredPrices = filteredPrices
        )
    }
}
