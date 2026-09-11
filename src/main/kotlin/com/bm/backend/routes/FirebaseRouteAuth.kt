package com.bm.backend.routes

import com.bm.backend.firebase.FirebaseAdminFactory
import com.bm.backend.models.ErrorResponse
import com.bm.backend.services.AccessControlService
import com.bm.backend.services.AdminAccessControlService
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.application
import io.ktor.server.application.log
import io.ktor.server.response.respond
import java.time.Instant

data class AuthenticatedFirebaseUser(
    val uid: String,
    val email: String?,
    val name: String?,
    val tokenIssuedAt: Instant,
    val tokenExpiresAt: Instant,
)

suspend fun ApplicationCall.requireAuthenticatedFirebaseUser(
    verifyToken: suspend (String) -> AuthenticatedFirebaseUser = ::verifyFirebaseIdToken
): AuthenticatedFirebaseUser? {
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
        verifyToken(idToken)
    } catch (e: FirebaseAuthException) {
        respond(
            HttpStatusCode.Unauthorized,
            ErrorResponse(message = "Invalid Firebase ID token: ${e.message}")
        )
        null
    }
}

/**
 * Requires a Firebase-authenticated caller with an active BmApp grant.
 * The returned email comes from the verified token and is normalized for all
 * downstream identity and persistence operations.
 */
suspend fun ApplicationCall.requireGrantedFirebaseUser(
    accessControlService: AccessControlService,
    verifyToken: suspend (String) -> AuthenticatedFirebaseUser = ::verifyFirebaseIdToken
): AuthenticatedFirebaseUser? {
    val authenticatedUser = requireAuthenticatedFirebaseUser(verifyToken) ?: return null
    val email = authenticatedUser.email?.trim()?.lowercase().orEmpty()

    if (!accessControlService.isEmailAllowed(email)) {
        application.log.warn(
            "AUDIT: Non-granted account attempted to access BmApp uid={} email={}",
            authenticatedUser.uid,
            email
        )
        respond(
            HttpStatusCode.Forbidden,
            ErrorResponse(
                message = "Esta cuenta no está autorizada para acceder a la aplicación. " +
                    "Contacta con el equipo de administración."
            )
        )
        return null
    }

    return authenticatedUser.copy(email = email)
}

internal fun verifyFirebaseIdToken(idToken: String): AuthenticatedFirebaseUser {
    FirebaseAdminFactory.init()
    val decodedToken = FirebaseAuth.getInstance().verifyIdToken(idToken)
    val issuedAt = Instant.ofEpochSecond(
        (decodedToken.claims["iat"] as? Number)?.toLong() ?: 0L
    )
    val expiresAt = Instant.ofEpochSecond(
        (decodedToken.claims["exp"] as? Number)?.toLong() ?: 0L
    )
    return AuthenticatedFirebaseUser(
        uid = decodedToken.uid,
        email = decodedToken.email,
        name = decodedToken.name,
        tokenIssuedAt = issuedAt,
        tokenExpiresAt = expiresAt,
    )
}

/**
 * Requires a Firebase-authenticated caller whose account is on the admin
 * allowlist (`admin_users`). Responds and returns null when the request is
 * not authorized.
 */
suspend fun ApplicationCall.requireAdminFirebaseUser(
    adminAccessControlService: AdminAccessControlService,
    action: String
): AuthenticatedFirebaseUser? {
    val authenticatedUser = requireAuthenticatedFirebaseUser() ?: return null

    if (!adminAccessControlService.isAdmin(authenticatedUser.email)) {
        application.log.warn(
            "AUDIT: Non-admin account attempted to {} uid={} email={}",
            action,
            authenticatedUser.uid,
            authenticatedUser.email
        )
        respond(
            HttpStatusCode.Forbidden,
            ErrorResponse(message = "Esta cuenta no está autorizada para acceder al panel de administración.")
        )
        return null
    }
    return authenticatedUser
}
