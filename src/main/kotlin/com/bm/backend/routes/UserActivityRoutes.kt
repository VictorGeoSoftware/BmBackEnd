package com.bm.backend.routes

import com.bm.backend.models.ErrorResponse
import com.bm.backend.models.UserActivityFirstConnectionListResponse
import com.bm.backend.models.UserActivityListResponse
import com.bm.backend.models.UserActivityMutationResponse
import com.bm.backend.services.UserActivityService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.application.log
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post

fun Route.userActivityRoutes(userActivityService: UserActivityService) {
    post("/user-activity/online") {
        try {
            val authenticatedUser = call.requireAuthenticatedFirebaseUser() ?: return@post
            val email = authenticatedUser.email?.trim().orEmpty()
            if (email.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse(message = "Authenticated user email is required"))
                return@post
            }
            val name = authenticatedUser.name?.trim().takeUnless { it.isNullOrBlank() }
                ?: email.substringBefore('@').ifBlank { email }

            userActivityService.setUserOnline(name = name, email = email)
            call.respond(
                HttpStatusCode.OK,
                UserActivityMutationResponse(
                    success = true,
                    message = "User marked as online"
                )
            )
        } catch (e: IllegalArgumentException) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(message = e.message ?: "Invalid request"))
        } catch (e: Exception) {
            call.application.log.error("Error setting user online: ${e.message}", e)
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorResponse(message = "Internal server error: ${e.message}")
            )
        }
    }

    post("/user-activity/offline") {
        try {
            val authenticatedUser = call.requireAuthenticatedFirebaseUser() ?: return@post
            val email = authenticatedUser.email?.trim().orEmpty()
            if (email.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse(message = "Authenticated user email is required"))
                return@post
            }
            val name = authenticatedUser.name?.trim().takeUnless { it.isNullOrBlank() }
                ?: email.substringBefore('@').ifBlank { email }

            userActivityService.setUserOffline(name = name, email = email)
            call.respond(
                HttpStatusCode.OK,
                UserActivityMutationResponse(
                    success = true,
                    message = "User marked as offline"
                )
            )
        } catch (e: IllegalArgumentException) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(message = e.message ?: "Invalid request"))
        } catch (e: Exception) {
            call.application.log.error("Error setting user offline: ${e.message}", e)
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorResponse(message = "Internal server error: ${e.message}")
            )
        }
    }

    post("/user-activity/proposals-response") {
        try {
            val authenticatedUser = call.requireAuthenticatedFirebaseUser() ?: return@post
            val email = authenticatedUser.email?.trim().orEmpty()
            if (email.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse(message = "Authenticated user email is required"))
                return@post
            }
            val name = authenticatedUser.name?.trim().takeUnless { it.isNullOrBlank() }
                ?: email.substringBefore('@').ifBlank { email }

            userActivityService.incrementMonthlyUsageCounter(name = name, email = email)
            call.respond(
                HttpStatusCode.OK,
                UserActivityMutationResponse(
                    success = true,
                    message = "User monthly usage counter incremented"
                )
            )
        } catch (e: IllegalArgumentException) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(message = e.message ?: "Invalid request"))
        } catch (e: Exception) {
            call.application.log.error("Error incrementing user monthly usage counter: ${e.message}", e)
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorResponse(message = "Internal server error: ${e.message}")
            )
        }
    }

    get("/user-activity/users") {
        try {
            call.respond(
                HttpStatusCode.OK,
                UserActivityListResponse(
                    success = true,
                    users = userActivityService.getUsersActivity()
                )
            )
        } catch (e: Exception) {
            call.application.log.error("Error fetching users activity: ${e.message}", e)
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorResponse(message = "Internal server error: ${e.message}")
            )
        }
    }

    get("/user-activity/users/first-connection") {
        try {
            call.respond(
                HttpStatusCode.OK,
                UserActivityFirstConnectionListResponse(
                    success = true,
                    users = userActivityService.getUsersFirstConnection()
                )
            )
        } catch (e: Exception) {
            call.application.log.error("Error fetching users first connection: ${e.message}", e)
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorResponse(message = "Internal server error: ${e.message}")
            )
        }
    }
}
