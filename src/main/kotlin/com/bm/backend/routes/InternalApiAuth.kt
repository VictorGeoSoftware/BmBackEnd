package com.bm.backend.routes

import com.bm.backend.models.ErrorResponse
import com.bm.backend.services.InternalApiAuthService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.log
import io.ktor.server.request.header
import io.ktor.server.request.path
import io.ktor.server.response.respond

/**
 * Responds 401 and returns false unless the call carries the internal API
 * token. Usage: `if (!call.requireInternalToken(service)) return@post`.
 */
suspend fun ApplicationCall.requireInternalToken(service: InternalApiAuthService): Boolean {
    if (service.isAuthorized(request.header(InternalApiAuthService.HEADER))) return true
    application.log.warn("Rejected internal endpoint call without a valid token: {}", request.path())
    respond(HttpStatusCode.Unauthorized, ErrorResponse(message = "Unauthorized"))
    return false
}
