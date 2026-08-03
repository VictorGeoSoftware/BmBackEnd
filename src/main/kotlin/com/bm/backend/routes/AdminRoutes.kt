package com.bm.backend.routes

import com.bm.backend.models.AdminResetDeviceRequest
import com.bm.backend.models.AdminResetDeviceResponse
import com.bm.backend.models.ErrorResponse
import com.bm.backend.services.AdminAuthService
import com.bm.backend.services.UserDataService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.application.log
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post

/**
 * Administrative endpoints guarded by [AdminAuthService] (shared-secret header).
 * Kept separate from user-facing routes so the authorization boundary is
 * explicit and easy to audit.
 */
fun Route.adminRoutes(
    userDataService: UserDataService,
    adminAuthService: AdminAuthService
) {
    // Clears the one-phone binding for an account so a replacement phone can
    // bind on the next login. Requires the admin shared secret.
    post("/admin/reset-device-binding") {
        if (!adminAuthService.enabled) {
            call.respond(
                HttpStatusCode.ServiceUnavailable,
                ErrorResponse(message = "Admin API is not configured")
            )
            return@post
        }

        val providedToken = call.request.headers[AdminAuthService.HEADER]
        if (!adminAuthService.isAuthorized(providedToken)) {
            call.application.log.warn("AUDIT: Unauthorized admin reset-device-binding attempt")
            call.respond(
                HttpStatusCode.Unauthorized,
                ErrorResponse(message = "Invalid or missing admin token")
            )
            return@post
        }

        try {
            val request = call.receive<AdminResetDeviceRequest>()
            val email = request.email?.trim().orEmpty()
            if (email.isBlank()) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse(message = "email is required")
                )
                return@post
            }

            val reset = userDataService.resetDeviceBinding(email)
            call.application.log.info(
                "AUDIT: Admin reset device binding for email={} rows={}",
                email,
                reset
            )
            call.respond(
                HttpStatusCode.OK,
                AdminResetDeviceResponse(email = email, reset = reset)
            )
        } catch (e: IllegalArgumentException) {
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse(message = e.message ?: "Invalid request")
            )
        } catch (e: Exception) {
            call.application.log.error("Error resetting device binding: ${e.message}", e)
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorResponse(message = "Internal server error: ${e.message}")
            )
        }
    }
}
