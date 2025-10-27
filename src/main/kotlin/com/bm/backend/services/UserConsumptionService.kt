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
        try {
            // Step 1: Extract data from PDF via Docling API
            println("Step 1: Starting Docling API extraction...")
            val doclingData = try {
                externalApiService.extractDataFromPdf(pdfFile)
            } catch (e: Exception) {
                throw Exception("Failed at Docling API extraction step: ${e.message}", e)
            }
            println("Step 1: Docling API extraction completed successfully")
            
            // Step 2: Process with N8N webhook
            println("Step 2: Starting N8N webhook processing (this may take 5+ minutes)...")
            val n8nResponse = try {
                externalApiService.processWithN8nWebhook(doclingData)
            } catch (e: Exception) {
                throw Exception("Failed at N8N webhook processing step: ${e.message}", e)
            }
            println("Step 2: N8N webhook processing completed successfully")
            
            // Step 3: Clean the consumption data
            println("Step 3: Cleaning consumption data...")
            val cleanedConsumptionData = n8nResponse.data.toCleanedData(n8nResponse.processedAt)
            
            // Step 4: Get filtered price table results based on tarifa
            println("Step 4: Getting filtered price tables for tarifa: ${cleanedConsumptionData.tarifa}")
            val filteredPrices = try {
                priceTableService.getAllPriceTableResults(cleanedConsumptionData.tarifa)
            } catch (e: Exception) {
                throw Exception("Failed at price table filtering step: ${e.message}", e)
            }
            
            // Step 5: Build consolidated response
            println("Step 5: Building consolidated response...")
            return ConsumptionReportResponse(
                success = true,
                doclingData = doclingData,
                consumptionData = cleanedConsumptionData,
                filteredPrices = filteredPrices
            )
        } catch (e: Exception) {
            println("Error in processConsumptionReportFromPdf: ${e.message}")
            throw Exception("Error processing consumption report from PDF: ${e.message}", e)
        }
    }
}
