package com.bm.backend.routes

import com.bm.backend.models.BatchPriceTablesRequest
import com.bm.backend.models.ErrorResponse
import com.bm.backend.models.PriceTableResponse
import com.bm.backend.services.PriceTableService
import com.bm.backend.services.ValidationException
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json

fun Route.priceTableRoutes(priceTableService: PriceTableService) {
    
    post("/batch-process-price-tables") {
        try {
            // Get raw request body for debugging
            val rawBody = call.receiveText()
            call.application.log.info("Received request body: $rawBody")
            
            // Try to parse as different possible structures
            val request: BatchPriceTablesRequest = try {
                // First try: direct list of PriceTableResponse
                Json.decodeFromString<List<PriceTableResponse>>(rawBody)
            } catch (e: Exception) {
                call.application.log.info("Failed to parse as List<PriceTableResponse>, trying single object: ${e.message}")
                try {
                    // Second try: single PriceTableResponse wrapped in list
                    val singleResponse = Json.decodeFromString<PriceTableResponse>(rawBody)
                    listOf(singleResponse)
                } catch (e2: Exception) {
                    call.application.log.error("Failed to parse request body as any expected format: ${e2.message}")
                    throw Exception("Invalid request format. Expected List<PriceTableResponse> or single PriceTableResponse. Original error: ${e.message}")
                }
            }
            
            val response = priceTableService.processBatchPriceTables(request)
            call.respond(HttpStatusCode.Created, response)
        } catch (e: ValidationException) {
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse(message = "Validation failed: ${e.message}")
            )
        } catch (e: Exception) {
            call.application.log.error("Error processing batch price tables: ${e.message}", e)
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorResponse(message = "Internal server error: ${e.message}")
            )
        }
    }

    get("/price-table-results") {
        try {
            val response = priceTableService.getAllPriceTableResults()
            call.respond(HttpStatusCode.OK, response)
        } catch (e: Exception) {
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorResponse(message = "Internal server error: ${e.message}")
            )
        }
    }

    delete("/clear-all-data") {
        try {
            val response = priceTableService.clearAllData()
            call.respond(HttpStatusCode.OK, response)
        } catch (e: Exception) {
            call.application.log.error("Error clearing all data: ${e.message}", e)
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorResponse(message = "Internal server error: ${e.message}")
            )
        }
    }
}
