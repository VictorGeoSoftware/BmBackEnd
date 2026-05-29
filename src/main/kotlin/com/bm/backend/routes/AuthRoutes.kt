package com.bm.backend.routes

import com.bm.backend.models.ErrorResponse
import com.bm.backend.models.UserActivityMutationResponse
import com.bm.backend.services.UserActivityService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.application.log
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post

fun Route.authRoutes(userActivityService: UserActivityService) {
    post("/auth/logout") {
        try {
            val authenticatedUser = call.requireAuthenticatedFirebaseUser() ?: return@post
            val email = authenticatedUser.email?.trim().orEmpty().lowercase()

            if (email.isBlank()) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse(message = "Authenticated user email is required")
                )
                return@post
            }

            val fallbackName = email.substringBefore('@').ifBlank { email }
            val name = authenticatedUser.name?.trim().takeUnless { it.isNullOrBlank() } ?: fallbackName

            userActivityService.setUserOffline(name = name, email = email)
            call.respond(
                HttpStatusCode.OK,
                UserActivityMutationResponse(
                    success = true,
                    message = "User logged out successfully"
                )
            )
        } catch (e: IllegalArgumentException) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(message = e.message ?: "Invalid request"))
        } catch (e: Exception) {
            call.application.log.error("Error logging out user: ${e.message}", e)
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorResponse(message = "Internal server error: ${e.message}")
            )
        }
    }
}
