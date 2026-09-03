package com.bm.backend.routes

import com.bm.backend.models.*
import com.bm.backend.services.ExternalApiService
import com.bm.backend.services.AdminAccessControlService
import com.bm.backend.services.PriceTableService
import com.bm.backend.services.PriceUpdatesNotifier
import com.bm.backend.services.ValidationException
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import net.logstash.logback.argument.StructuredArguments.kv
import java.io.File
import java.nio.file.Files

fun Route.priceTableRoutes(
    priceTableService: PriceTableService,
    externalApiService: ExternalApiService,
    priceUpdatesNotifier: PriceUpdatesNotifier,
    adminAccessControlService: AdminAccessControlService
) {
    // NOTE: deliberately NOT Firebase-authenticated. This is a
    // machine-to-machine endpoint: the n8n workflow posts here as
    // http://backend-prod:8081/api/v1/batch-process-price-tables over the
    // internal Docker network and holds no Firebase token. External access is
    // blocked at the proxy instead (`deny all` in BmInfra/nginx/nginx.conf),
    // which the internal call bypasses entirely.
    post("/batch-process-price-tables") {

        try {
            val rawBody = call.receiveText()
            // Log the size, never the payload. This ran at INFO with the whole
            // body inline "for debugging"; with logs now shipped to Loki and
            // retained 30 days that is unbounded storage driven by request size,
            // and it puts extracted document content into log storage.
            call.application.log.info(
                "Batch price tables request received {}",
                kv("payloadBytes", rawBody.length)
            )

            // Parse with the same normalization strategy used by upload-price-proposal flow.
            // Accepts either backend PriceTableResponse JSON or raw Docling extraction JSON.
            val request: BatchPriceTablesRequest = externalApiService.parseBatchPriceTablesPayload(rawBody)
            
            val response = priceTableService.processBatchPriceTables(request)
            priceUpdatesNotifier.notify(
                PriceUpdatesNotification(
                    eventType = PriceUpdatesEventType.PRICE_PROPOSALS_UPSERTED,
                    changedCount = response.processed_files
                )
            )
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
        // BmApp reads this and its users are regular granted accounts, so
        // authentication is required but admin rights are not.
        if (call.requireAuthenticatedFirebaseUser() == null) return@get

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

    get("/price-table-tax-settings") {
        if (call.requireAdminFirebaseUser(adminAccessControlService, "read price table tax settings") == null) return@get

        try {
            val response = priceTableService.getTaxSettings()
            call.respond(HttpStatusCode.OK, response)
        } catch (e: Exception) {
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorResponse(message = "Internal server error: ${e.message}")
            )
        }
    }

    patch("/price-table-tax-settings") {
        if (call.requireAdminFirebaseUser(adminAccessControlService, "update price table tax settings") == null) return@patch

        try {
            val request = call.receive<UpdateTaxSettingsRequest>()
            val response = priceTableService.updateTaxSettings(
                iva = request.iva,
                impuestoElectrico = request.impuestoElectrico
            )
            priceUpdatesNotifier.notify(
                PriceUpdatesNotification(
                    eventType = PriceUpdatesEventType.PRICE_PROPOSALS_UPSERTED,
                    changedCount = 0
                )
            )
            call.respond(HttpStatusCode.OK, response)
        } catch (e: ValidationException) {
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse(message = "Validation failed: ${e.message}")
            )
        } catch (e: Exception) {
            call.application.log.error("Error updating tax settings: ${e.message}", e)
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorResponse(message = "Internal server error: ${e.message}")
            )
        }
    }

    delete("/clear-all-data") {
        if (call.requireAdminFirebaseUser(adminAccessControlService, "clear all price table data") == null) return@delete

        try {
            val response = priceTableService.clearAllData()
            priceUpdatesNotifier.notify(
                PriceUpdatesNotification(
                    eventType = PriceUpdatesEventType.PRICE_PROPOSALS_CLEARED,
                    changedCount = response.deleted_rows
                )
            )
            call.respond(HttpStatusCode.OK, response)
        } catch (e: Exception) {
            call.application.log.error("Error clearing all data: ${e.message}", e)
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorResponse(message = "Internal server error: ${e.message}")
            )
        }
    }

    post("/fetch-total-prices") {
        if (call.requireAdminFirebaseUser(adminAccessControlService, "trigger the total prices workflow") == null) return@post

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

    delete("/price-table-results") {
        if (call.requireAdminFirebaseUser(adminAccessControlService, "delete price proposals") == null) return@delete

        try {
            val request = call.receive<DeleteSelectedPriceTablesRequest>()
            val response = priceTableService.deleteSelectedPriceTables(request.ids)
            if (response.deleted_ids.isNotEmpty()) {
                priceUpdatesNotifier.notify(
                    PriceUpdatesNotification(
                        eventType = PriceUpdatesEventType.PRICE_PROPOSALS_DELETED,
                        changedIds = response.deleted_ids,
                        changedCount = response.deleted_ids.size
                    )
                )
            }
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

    post("/upload-price-proposal") {
        if (call.requireAdminFirebaseUser(adminAccessControlService, "upload a price proposal") == null) return@post

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
            // Summary only — the full extraction JSON used to be logged inline.
            call.application.log.info(
                "Price tables extracted from PDF {}",
                kv("fileName", uploadedFileName ?: tempFile!!.name)
            )

            val storageResponse = priceTableService.processBatchPriceTables(listOf(extractedResponse))
            priceUpdatesNotifier.notify(
                PriceUpdatesNotification(
                    eventType = PriceUpdatesEventType.PRICE_PROPOSALS_UPSERTED,
                    changedCount = storageResponse.processed_files
                )
            )

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
