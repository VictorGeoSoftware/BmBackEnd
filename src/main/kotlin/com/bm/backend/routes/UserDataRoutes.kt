package com.bm.backend.routes

import com.bm.backend.models.ErrorResponse
import com.bm.backend.models.UserDataRequest
import com.bm.backend.services.UserDataService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.application.log
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post

fun Route.userDataRoutes(userDataService: UserDataService) {
    post("/user-data") {
        try {
            val authenticatedUser = call.requireAuthenticatedFirebaseUser() ?: return@post
            val request = call.receive<UserDataRequest>()
            val uid = request.uid

            if (uid.isNullOrBlank()) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse(message = "uid is required")
                )
                return@post
            }

            if (authenticatedUser.uid != uid) {
                call.respond(
                    HttpStatusCode.Unauthorized,
                    ErrorResponse(message = "Token uid does not match request uid")
                )
                return@post
            }

            userDataService.upsertUserData(
                uid = uid,
                email = request.email ?: authenticatedUser.email,
                displayName = request.displayName ?: authenticatedUser.name,
                photoURL = request.photoURL,
                providerIds = request.providerIds,
                tokenIssuedAt = authenticatedUser.tokenIssuedAt,
                tokenExpiresAt = authenticatedUser.tokenExpiresAt
            )

            call.respond(HttpStatusCode.OK)
        } catch (e: IllegalArgumentException) {
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse(message = e.message ?: "Invalid request")
            )
        } catch (e: Exception) {
            call.application.log.error("Error syncing user data: ${e.message}", e)
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorResponse(message = "Internal server error: ${e.message}")
            )
        }
    }
}
