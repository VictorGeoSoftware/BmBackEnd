package com.bm.backend.routes

import com.bm.backend.firebase.FirebaseAdminFactory
import com.bm.backend.models.ErrorResponse
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import java.time.Instant

data class AuthenticatedFirebaseUser(
    val uid: String,
    val email: String?,
    val name: String?,
    val tokenIssuedAt: Instant,
    val tokenExpiresAt: Instant,
)

suspend fun ApplicationCall.requireAuthenticatedFirebaseUser(): AuthenticatedFirebaseUser? {
    val authHeader = request.headers[HttpHeaders.Authorization]
    if (authHeader.isNullOrBlank() || !authHeader.startsWith("Bearer ")) {
        respond(
            HttpStatusCode.Unauthorized,
            ErrorResponse(message = "Missing or invalid Authorization header")
        )
        return null
    }

    val idToken = authHeader.removePrefix("Bearer ").trim()
    if (idToken.isBlank()) {
        respond(
            HttpStatusCode.Unauthorized,
            ErrorResponse(message = "Missing Firebase ID token")
        )
        return null
    }

    return try {
        FirebaseAdminFactory.init()
        val decodedToken = FirebaseAuth.getInstance().verifyIdToken(idToken)
        val issuedAt = Instant.ofEpochSecond(
            (decodedToken.claims["iat"] as? Number)?.toLong() ?: 0L
        )
        val expiresAt = Instant.ofEpochSecond(
            (decodedToken.claims["exp"] as? Number)?.toLong() ?: 0L
        )
        AuthenticatedFirebaseUser(
            uid = decodedToken.uid,
            email = decodedToken.email,
            name = decodedToken.name,
            tokenIssuedAt = issuedAt,
            tokenExpiresAt = expiresAt,
        )
    } catch (e: FirebaseAuthException) {
        respond(
            HttpStatusCode.Unauthorized,
            ErrorResponse(message = "Invalid Firebase ID token: ${e.message}")
        )
        null
    }
}
