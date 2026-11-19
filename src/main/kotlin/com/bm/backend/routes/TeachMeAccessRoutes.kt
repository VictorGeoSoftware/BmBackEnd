package com.bm.backend.routes

import com.bm.backend.models.ErrorResponse
import com.bm.backend.models.TeachWorkbookAuthDecision
import com.bm.backend.services.AdminAccessControlService
import com.bm.backend.services.TeachMeAccessPolicy
import com.bm.backend.services.TeachClarificationAdapter
import com.bm.backend.services.TeachSupplierDraftAdapter
import com.bm.backend.services.fixedTeachClarificationAdapter
import com.bm.backend.services.fixedTeachSupplierDraftAdapter
import com.google.firebase.auth.FirebaseAuthException
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

internal fun Route.teachMeAccessRoutes(
    adminAccess: AdminAccessControlService,
    policy: TeachMeAccessPolicy,
    clarificationEnabled: Boolean = false,
    clarificationAdapter: TeachClarificationAdapter = fixedTeachClarificationAdapter,
    supplierDraftEnabled: Boolean = false,
    supplierDraftAdapter: TeachSupplierDraftAdapter = fixedTeachSupplierDraftAdapter,
    verifyToken: (String) -> AuthenticatedFirebaseUser = ::verifyFirebaseIdToken
) {
    if (!policy.enabled) return

    get("/teach-me/check-access") {
        call.response.header(HttpHeaders.CacheControl, "no-store")
        val admin = call.requireAdminFirebaseUser(adminAccess, "access Teach Me") { verifyToken(it) } ?: return@get
        if (!policy.allows(admin.email)) {
            call.respond(HttpStatusCode.Forbidden, ErrorResponse(message = "Acceso a Enséñame no concedido."))
            return@get
        }
        call.respond(HttpStatusCode.NoContent)
    }
    teachWorkbookMessageRoutes(clarificationEnabled = clarificationEnabled, clarificationAdapter = clarificationAdapter,
        supplierDraftEnabled = supplierDraftEnabled, supplierDraftAdapter = supplierDraftAdapter) { token ->
        val user = try { verifyToken(token) } catch (_: FirebaseAuthException) {
            return@teachWorkbookMessageRoutes TeachWorkbookAuthDecision.InvalidToken
        }
        when {
            !adminAccess.isAdmin(user.email) -> TeachWorkbookAuthDecision.NotAdmin
            !policy.allows(user.email) -> TeachWorkbookAuthDecision.NotNominated
            else -> TeachWorkbookAuthDecision.Allowed
        }
    }
}
