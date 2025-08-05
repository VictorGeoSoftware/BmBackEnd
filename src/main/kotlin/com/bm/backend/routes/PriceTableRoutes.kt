package com.bm.backend.routes

import com.bm.backend.models.*
import com.bm.backend.services.PriceTableService
import com.bm.backend.services.ValidationException
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.priceTableRoutes(priceTableService: PriceTableService) {
    
    post("/store-price-tables") {
        try {
            val request = call.receive<StorePriceTablesRequest>()
            val response = priceTableService.storePriceTables(request)
            call.respond(HttpStatusCode.Created, response)
        } catch (e: ValidationException) {
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse(message = "Validation failed", details = e.message)
            )
        } catch (e: Exception) {
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorResponse(message = "Internal server error", details = e.message)
            )
        }
    }

    post("/batch-process-price-tables") {
        try {
            val request = call.receive<BatchPriceTablesRequest>()
            val response = priceTableService.processBatchPriceTables(request)
            call.respond(HttpStatusCode.Created, response)
        } catch (e: ValidationException) {
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse(message = "Validation failed", details = e.message)
            )
        } catch (e: Exception) {
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorResponse(message = "Internal server error", details = e.message)
            )
        }
    }

    get("/price-tables") {
        try {
            val limit = call.parameters["limit"]?.toIntOrNull()
            val offset = call.parameters["offset"]?.toIntOrNull()
            val filename = call.parameters["filename"]
            val source = call.parameters["source"]
            
            if (limit != null && limit <= 0) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse(message = "Limit must be positive")
                )
                return@get
            }
            
            if (offset != null && offset < 0) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse(message = "Offset must be non-negative")
                )
                return@get
            }

            val response = priceTableService.getAllPriceTables(limit, offset, filename, source)
            call.respond(HttpStatusCode.OK, response)
        } catch (e: NumberFormatException) {
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse(message = "Invalid limit or offset format")
            )
        } catch (e: Exception) {
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorResponse(message = "Internal server error", details = e.message)
            )
        }
    }

    get("/price-tables/{id}") {
        try {
            val id = call.parameters["id"]?.toIntOrNull()
                ?: return@get call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse(message = "Invalid ID format")
                )

            val response = priceTableService.getPriceTableById(id)
            if (response != null) {
                call.respond(HttpStatusCode.OK, response)
            } else {
                call.respond(
                    HttpStatusCode.NotFound,
                    ErrorResponse(message = "Record not found")
                )
            }
        } catch (e: Exception) {
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorResponse(message = "Internal server error", details = e.message)
            )
        }
    }

    // New endpoints to fetch transposed table data
    get("/prices-1") {
        try {
            val limit = call.parameters["limit"]?.toIntOrNull()
            val offset = call.parameters["offset"]?.toIntOrNull()
            val filename = call.parameters["filename"]
            
            val response = priceTableService.getPrices1Data(limit, offset, filename)
            call.respond(HttpStatusCode.OK, response)
        } catch (e: Exception) {
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorResponse(message = "Internal server error", details = e.message)
            )
        }
    }

    get("/prices-2") {
        try {
            val limit = call.parameters["limit"]?.toIntOrNull()
            val offset = call.parameters["offset"]?.toIntOrNull()
            val filename = call.parameters["filename"]
            
            val response = priceTableService.getPrices2Data(limit, offset, filename)
            call.respond(HttpStatusCode.OK, response)
        } catch (e: Exception) {
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorResponse(message = "Internal server error", details = e.message)
            )
        }
    }

    get("/prices-3") {
        try {
            val limit = call.parameters["limit"]?.toIntOrNull()
            val offset = call.parameters["offset"]?.toIntOrNull()
            val filename = call.parameters["filename"]
            
            val response = priceTableService.getPrices3Data(limit, offset, filename)
            call.respond(HttpStatusCode.OK, response)
        } catch (e: Exception) {
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorResponse(message = "Internal server error", details = e.message)
            )
        }
    }
}
