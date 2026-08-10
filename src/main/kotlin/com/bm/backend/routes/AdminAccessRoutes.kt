package com.bm.backend.routes

import com.bm.backend.models.AdminAccessResponse
import com.bm.backend.services.AdminAccessControlService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * Lightweight admin-access probe consumed by BmWeb right after login: it lets
 * the dashboard reject non-admin accounts before rendering any admin UI.
 *
 * Authorization: same as every admin endpoint — the caller must present a
 * valid Firebase ID token whose account is on the admin allowlist
 * (`admin_users`). Responds 200 with the admin's email, or 403 otherwise.
 */
fun Route.adminAccessRoutes(
    adminAccessControlService: AdminAccessControlService
) {
    get("/admin/check-access") {
        val admin = call.requireAdminFirebaseUser(
            adminAccessControlService,
            "check admin access"
        ) ?: return@get

        call.respond(
            HttpStatusCode.OK,
            AdminAccessResponse(success = true, email = admin.email)
        )
    }
}
