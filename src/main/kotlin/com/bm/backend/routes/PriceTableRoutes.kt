package com.bm.backend.routes

import com.bm.backend.models.BatchPriceTablesRequest
import com.bm.backend.models.DeleteSelectedPriceTablesRequest
import com.bm.backend.models.ErrorResponse
import com.bm.backend.models.TriggerWorkflowResponse
import com.bm.backend.models.UploadPriceProposalResponse
import com.bm.backend.services.ExternalApiService
import com.bm.backend.services.PriceTableService
import com.bm.backend.services.ValidationException
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files

fun Route.priceTableRoutes(
    priceTableService: PriceTableService,
    externalApiService: ExternalApiService
) {
    post("/batch-process-price-tables") {
        try {
            // Get raw request body for debugging
            val rawBody = call.receiveText()
            call.application.log.info("Received request body: $rawBody")

            // Parse with the same normalization strategy used by upload-price-proposal flow.
            // Accepts either backend PriceTableResponse JSON or raw Docling extraction JSON.
            val request: BatchPriceTablesRequest = externalApiService.parseBatchPriceTablesPayload(rawBody)
            
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
            val tarifaType = call.request.queryParameters["tarifaType"]
            val response = priceTableService.getAllPriceTableResults(tarifaType)
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

    delete("/price-table-results") {
        try {
            val request = call.receive<DeleteSelectedPriceTablesRequest>()
            val response = priceTableService.deleteSelectedPriceTables(request.ids)
            call.respond(HttpStatusCode.OK, response)
        } catch (e: ValidationException) {
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse(message = "Validation failed: ${e.message}")
            )
        } catch (e: Exception) {
            call.application.log.error("Error deleting selected price proposals: ${e.message}", e)
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorResponse(message = "Internal server error: ${e.message}")
            )
        }
    }

    post("/fetch-total-prices") {
        try {
            val response = externalApiService.triggerFetchTotalPricesWorkflow()
            call.respond(HttpStatusCode.OK, response)
        } catch (e: Exception) {
            call.application.log.error("Error triggering Total prices workflow: ${e.message}", e)
            call.respond(
                HttpStatusCode.InternalServerError,
                TriggerWorkflowResponse(
                    success = false,
                    message = "Failed to trigger Total prices workflow",
                    details = e.message
                )
            )
        }
    }

    post("/upload-price-proposal") {
        var tempFile: File? = null
        var uploadedFileName: String? = null

        try {
            val multipartData = call.receiveMultipart()

            multipartData.forEachPart { part ->
                when (part) {
                    is PartData.FileItem -> {
                        if (tempFile != null) {
                            part.dispose()
                            return@forEachPart
                        }

                        val originalFileName = part.originalFileName ?: "uploaded.pdf"
                        if (!originalFileName.endsWith(".pdf", ignoreCase = true)) {
                            throw ValidationException("Only PDF files are accepted")
                        }

                        uploadedFileName = originalFileName
                        tempFile = Files.createTempFile("price_proposal_", ".pdf").toFile()

                        part.streamProvider().use { input ->
                            tempFile!!.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                    }

                    else -> {}
                }
                part.dispose()
            }

            if (tempFile == null) {
                throw ValidationException("No PDF file provided")
            }

            val extractedResponse = externalApiService.extractPriceTablesFromPdf(tempFile!!)
            call.application.log.info(
                "Extracted price tables response for file ${uploadedFileName ?: tempFile!!.name}: ${Json.encodeToString(extractedResponse)}"
            )

            val storageResponse = priceTableService.processBatchPriceTables(listOf(extractedResponse))

            call.respond(
                HttpStatusCode.Created,
                UploadPriceProposalResponse(
                    success = true,
                    message = "Price proposal processed successfully",
                    extracted = extractedResponse,
                    storage = storageResponse
                )
            )
        } catch (e: ValidationException) {
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse(message = "Validation failed: ${e.message}")
            )
        } catch (e: Exception) {
            call.application.log.error("Error uploading price proposal: ${e.message}", e)
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorResponse(message = "Internal server error: ${e.message}")
            )
        } finally {
            tempFile?.delete()
        }
    }
}
