package com.bm.backend.routes

import com.bm.backend.models.PremiumReportResult
import com.bm.backend.models.ReportDatasetRejection
import com.bm.backend.models.ReportDatasetRequest
import com.bm.backend.models.ReportErrorResponse
import com.bm.backend.plugins.requestId
import com.bm.backend.services.AccessControlService
import com.bm.backend.services.PremiumReportService
import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.application
import io.ktor.server.application.call
import io.ktor.server.application.log
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.post

private const val PREMIUM_REQUIRED = "premium_required"
private const val INVALID_REQUEST = "invalid_request"
private const val REPORT_UNAVAILABLE = "report_unavailable"
private const val REPORT_GENERATION_FAILED = "report_generation_failed"

private val fixedMessages = mapOf(
    PREMIUM_REQUIRED to "El informe completo requiere el plan Premium.",
    INVALID_REQUEST to "La solicitud del informe no es válida.",
    REPORT_UNAVAILABLE to "El informe completo todavía no está disponible.",
    REPORT_GENERATION_FAILED to "No se ha podido generar el informe. Inténtalo de nuevo más tarde.",
    ReportDatasetRejection.STUDY_NOT_FOUND.code to
        "El estudio no está disponible o ha caducado. Vuelve a consultar el suministro.",
    ReportDatasetRejection.STUDY_NOT_READY.code to "El estudio todavía no ha terminado."
)
private const val VALIDATION_MESSAGE =
    "Los datos de la comparativa no coinciden con el estudio. Vuelve a generar la comparativa."

/**
 * `POST /reports/premium-pdf`: authentication → grant → Premium capability (from the current DB tier, before the
 * body is read) → body → owned/valid study → render. Only maps outcomes; logic lives in [PremiumReportService].
 */
fun Route.premiumReportRoutes(
    premiumReportService: PremiumReportService,
    accessControlService: AccessControlService,
    verifyToken: suspend (String) -> AuthenticatedFirebaseUser = ::verifyFirebaseIdToken
) {
    post("/reports/premium-pdf") {
        val user = call.requireGrantedFirebaseUser(accessControlService, verifyToken) ?: return@post
        if (!premiumReportService.isAllowed(user)) {
            return@post call.reportError(HttpStatusCode.Forbidden, PREMIUM_REQUIRED, user.uid)
        }
        val request = try {
            call.receive<ReportDatasetRequest>()
        } catch (e: Exception) {
            return@post call.reportError(HttpStatusCode.BadRequest, INVALID_REQUEST, user.uid)
        }
        when (val result = premiumReportService.generate(request, user)) {
            is PremiumReportResult.Generated -> {
                call.application.log.info("PREMIUM_REPORT outcome=generated requestId={} uid={}", call.requestId, user.uid)
                call.response.headers.append(
                    HttpHeaders.ContentDisposition,
                    ContentDisposition.Attachment
                        .withParameter(ContentDisposition.Parameters.FileName, "informe_completo_${System.currentTimeMillis()}.pdf")
                        .toString()
                )
                call.respondBytes(result.bytes, ContentType.Application.Pdf, HttpStatusCode.OK)
            }
            PremiumReportResult.Forbidden -> call.reportError(HttpStatusCode.Forbidden, PREMIUM_REQUIRED, user.uid)
            is PremiumReportResult.Rejected -> {
                val status = when (result.reason) {
                    ReportDatasetRejection.STUDY_NOT_FOUND -> HttpStatusCode.NotFound
                    ReportDatasetRejection.STUDY_NOT_READY -> HttpStatusCode.Conflict
                    else -> HttpStatusCode.UnprocessableEntity
                }
                call.reportError(status, result.reason.code, user.uid)
            }
            PremiumReportResult.Unavailable -> call.reportError(HttpStatusCode.NotImplemented, REPORT_UNAVAILABLE, user.uid)
            is PremiumReportResult.Failed -> {
                call.application.log.error(
                    "PREMIUM_REPORT render failed errorType={} requestId={} uid={}", result.errorType, call.requestId, user.uid
                )
                call.reportError(HttpStatusCode.InternalServerError, REPORT_GENERATION_FAILED, user.uid)
            }
        }
    }
}

private suspend fun ApplicationCall.reportError(status: HttpStatusCode, code: String, uid: String) {
    application.log.info("PREMIUM_REPORT outcome={} requestId={} uid={}", code, requestId, uid)
    respond(
        status,
        ReportErrorResponse(code = code, message = fixedMessages[code] ?: VALIDATION_MESSAGE, requestId = requestId)
    )
}
