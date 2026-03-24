package com.bm.backend.routes

import com.bm.backend.firebase.FirebaseAdminFactory
import com.bm.backend.models.ErrorResponse
import com.bm.backend.models.UserDataRequest
import com.bm.backend.services.UserDataService
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.userDataRoutes(userDataService: UserDataService) {
    post("/user-data") {
        try {
            val authHeader = call.request.headers[HttpHeaders.Authorization]
            if (authHeader.isNullOrBlank() || !authHeader.startsWith("Bearer ")) {
                call.respond(
                    HttpStatusCode.Unauthorized,
                    ErrorResponse(message = "Missing or invalid Authorization header")
                )
                return@post
            }

            val idToken = authHeader.removePrefix("Bearer ").trim()
            if (idToken.isBlank()) {
                call.respond(
                    HttpStatusCode.Unauthorized,
                    ErrorResponse(message = "Missing Firebase ID token")
                )
                return@post
            }

            FirebaseAdminFactory.init()
            val decodedToken = FirebaseAuth.getInstance().verifyIdToken(idToken)
            val request = call.receive<UserDataRequest>()
            val uid = request.uid

            if (uid.isNullOrBlank()) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse(message = "uid is required")
                )
                return@post
            }

            if (decodedToken.uid != uid) {
                call.respond(
                    HttpStatusCode.Unauthorized,
                    ErrorResponse(message = "Token uid does not match request uid")
                )
                return@post
            }

            val tokenIssuedAt = (decodedToken.claims["iat"] as? Number)?.toLong() ?: 0L
            val tokenExpiresAt = (decodedToken.claims["exp"] as? Number)?.toLong() ?: 0L

            userDataService.upsertUserData(
                uid = uid,
                email = request.email ?: decodedToken.email,
                displayName = request.displayName ?: decodedToken.name,
                photoURL = request.photoURL,
                providerIds = request.providerIds,
                tokenIssuedAt = tokenIssuedAt,
                tokenExpiresAt = tokenExpiresAt
            )

            call.respond(HttpStatusCode.OK)
        } catch (e: FirebaseAuthException) {
            call.respond(
                HttpStatusCode.Unauthorized,
                ErrorResponse(message = "Invalid Firebase ID token: ${e.message}")
            )
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
