package com.bm.backend.routes

import com.bm.backend.models.ErrorResponse
import com.bm.backend.services.AdminAccessControlService
import com.bm.backend.services.TeachMeAccessPolicy
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

fun Route.teachMeAccessRoutes(
    adminAccess: AdminAccessControlService,
    policy: TeachMeAccessPolicy,
    verifyToken: suspend (String) -> AuthenticatedFirebaseUser = ::verifyFirebaseIdToken
) {
    if (!policy.enabled) return

    get("/teach-me/check-access") {
        call.response.header(HttpHeaders.CacheControl, "no-store")
        val admin = call.requireAdminFirebaseUser(adminAccess, "access Teach Me", verifyToken) ?: return@get
        if (!policy.allows(admin.email)) {
            call.respond(HttpStatusCode.Forbidden, ErrorResponse(message = "Acceso a Enséñame no concedido."))
            return@get
        }
        call.respond(HttpStatusCode.NoContent)
    }
}
