package com.bm.backend.routes

import com.bm.backend.models.*
import com.bm.backend.services.JobService
import com.bm.backend.services.UserConsumptionService
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.nio.file.Files

fun Route.userConsumptionRoutes(
    userConsumptionService: UserConsumptionService,
    jobService: JobService
) {
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
                tempFile?.delete()
                return@post
            }
            
            if (tempFile == null) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse(message = "No PDF file provided")
                )
                return@post
            }
            
            // Create a job and return immediately
            val jobId = jobService.createJob()
            val pdfFile = tempFile!!
            
            // Process asynchronously in background
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    call.application.log.info("Starting background processing for job: $jobId")
                    jobService.updateJobStatus(jobId, JobStatus.PROCESSING, progress = 0)
                    
                    // Process the PDF through the orchestration flow
                    val response = userConsumptionService.processConsumptionReportFromPdf(pdfFile)
                    
                    // Mark job as completed
                    jobService.completeJob(jobId, response)
                    call.application.log.info("Job $jobId completed successfully")
                    
                } catch (e: Exception) {
                    call.application.log.error("Job $jobId failed: ${e.message}", e)
                    jobService.failJob(jobId, e.message ?: "Unknown error")
                } finally {
                    // Clean up temp file
                    pdfFile.delete()
                }
            }
            
            // Return 202 Accepted with job ID immediately
            call.respond(
                HttpStatusCode.Accepted,
                JobResponse(
                    jobId = jobId,
                    status = "accepted",
                    message = "Your request is being processed. Use the jobId to check status."
                )
            )
            
        } catch (e: Exception) {
            call.application.log.error("Error processing consumption report from PDF: ${e.message}", e)
            tempFile?.delete()
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorResponse(message = "Internal server error: ${e.message}")
            )
        }
    }
    
    get("/consumption-report-status/{jobId}") {
        val jobId = call.parameters["jobId"]
        
        if (jobId == null) {
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse(message = "Job ID is required")
            )
            return@get
        }
        
        val job = jobService.getJob(jobId)
        
        if (job == null) {
            call.respond(
                HttpStatusCode.NotFound,
                ErrorResponse(message = "Job not found")
            )
            return@get
        }
        
        call.respond(
            HttpStatusCode.OK,
            JobStatusResponse(
                jobId = job.jobId,
                status = job.status,
                message = when (job.status) {
                    JobStatus.PENDING -> "Job is pending"
                    JobStatus.PROCESSING -> "Job is being processed"
                    JobStatus.COMPLETED -> "Job completed successfully"
                    JobStatus.FAILED -> "Job failed"
                },
                progress = job.progress,
                result = job.result,
                error = job.error
            )
        )
    }
    
    get("/consumption-report-result/{jobId}") {
        val jobId = call.parameters["jobId"]
        
        if (jobId == null) {
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse(message = "Job ID is required")
            )
            return@get
        }
        
        val job = jobService.getJob(jobId)
        
        if (job == null) {
            call.respond(
                HttpStatusCode.NotFound,
                ErrorResponse(message = "Job not found")
            )
            return@get
        }
        
        when (job.status) {
            JobStatus.COMPLETED -> {
                if (job.result != null) {
                    call.respond(HttpStatusCode.OK, job.result!!)
                } else {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        ErrorResponse(message = "Job completed but result is missing")
                    )
                }
            }
            JobStatus.FAILED -> {
                call.respond(
                    HttpStatusCode.InternalServerError,
                    ErrorResponse(message = job.error ?: "Job failed")
                )
            }
            JobStatus.PROCESSING, JobStatus.PENDING -> {
                call.respond(
                    HttpStatusCode.Accepted,
                    ErrorResponse(message = "Job is still processing")
                )
            }
        }
    }

    post("/consumption-report-refresh/{jobId}") {
        val jobId = call.parameters["jobId"]

        if (jobId == null) {
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse(message = "Job ID is required")
            )
            return@post
        }

        val job = jobService.getJob(jobId)
        if (job == null) {
            call.respond(
                HttpStatusCode.NotFound,
                ErrorResponse(message = "Job not found")
            )
            return@post
        }

        if (job.status != JobStatus.COMPLETED || job.result == null) {
            call.respond(
                HttpStatusCode.Conflict,
                ErrorResponse(message = "Only completed jobs can refresh proposals")
            )
            return@post
        }

        try {
            val latestProposals = userConsumptionService.refreshProposals(job.result!!.consumptionData)
            val taxSettings = userConsumptionService.getCurrentTaxSettings()
            val refreshedResult = job.result!!.copy(
                proposals = latestProposals,
                iva = taxSettings.iva,
                impuestoElectrico = taxSettings.impuestoElectrico
            )
            jobService.completeJob(jobId, refreshedResult)
            call.respond(HttpStatusCode.OK, refreshedResult)
        } catch (e: Exception) {
            call.application.log.error("Error refreshing proposals for job $jobId: ${e.message}", e)
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorResponse(message = "Failed to refresh proposals: ${e.message}")
            )
        }
    }
}
