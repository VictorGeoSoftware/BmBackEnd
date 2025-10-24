package com.bm.backend.routes

import com.bm.backend.models.ErrorResponse
import com.bm.backend.models.UserConsumption
import com.bm.backend.models.UserConsumptionRequest
import com.bm.backend.models.toDomainModel
import com.bm.backend.services.UserConsumptionService
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*
import java.io.File
import java.nio.file.Files

fun Route.userConsumptionRoutes(userConsumptionService: UserConsumptionService) {
    post("/consumption-report") {
        try {
            val consumptionRequest = call.receive<UserConsumptionRequest>()
            val consumptionReport = consumptionRequest.toDomainModel()
            userConsumptionService.processConsumptionReport(consumptionReport)

            call.respond(HttpStatusCode.OK)
        } catch (e: Exception) {
            call.application.log.error("Error processing consumption report: ${e.message}", e)
            e.printStack()
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorResponse(message = "Internal server error: ${e.message}")
            )
        }
    }
    
    post("/fetch-user-consumption-report") {
        var tempFile: File? = null
        var errorResponse: Pair<HttpStatusCode, ErrorResponse>? = null
        
        try {
            val multipartData = call.receiveMultipart()
            
            multipartData.forEachPart { part ->
                when (part) {
                    is PartData.FileItem -> {
                        val fileName = part.originalFileName ?: "uploaded.pdf"
                        
                        // Validate file type
                        if (!fileName.endsWith(".pdf", ignoreCase = true)) {
                            errorResponse = HttpStatusCode.BadRequest to ErrorResponse(message = "Only PDF files are accepted")
                        } else if (tempFile == null) {
                            // Create temp file
                            tempFile = Files.createTempFile("consumption_report_", ".pdf").toFile()
                            
                            // Write uploaded file to temp file
                            part.streamProvider().use { input ->
                                tempFile!!.outputStream().use { output ->
                                    input.copyTo(output)
                                }
                            }
                        }
                    }
                    else -> {}
                }
                part.dispose()
            }
            
            // Check for validation errors
            if (errorResponse != null) {
                call.respond(errorResponse!!.first, errorResponse!!.second)
                return@post
            }
            
            if (tempFile == null) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse(message = "No PDF file provided")
                )
                return@post
            }
            
            // Process the PDF through the orchestration flow
            val response = userConsumptionService.processConsumptionReportFromPdf(tempFile!!)
            
            call.respond(HttpStatusCode.OK, response)
            
        } catch (e: Exception) {
            call.application.log.error("Error processing consumption report from PDF: ${e.message}", e)
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorResponse(message = "Internal server error: ${e.message}")
            )
        } finally {
            // Clean up temp file
            tempFile?.delete()
        }
    }
    
    get("/get-user-consumption-report") {
        try {
            val report = userConsumptionService.getConsumptionReport()
            if (report != null) {
                call.respond(HttpStatusCode.OK, report)
            } else {
                call.respond(
                    HttpStatusCode.NotFound,
                    ErrorResponse(message = "No consumption report available")
                )
            }
        } catch (e: Exception) {
            call.application.log.error("Error fetching consumption report: ${e.message}", e)
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorResponse(message = "Internal server error: ${e.message}")
            )
        }
    }
}
