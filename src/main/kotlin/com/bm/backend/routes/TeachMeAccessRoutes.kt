package com.bm.backend.routes

import com.bm.backend.models.ErrorResponse
import com.bm.backend.models.TeachWorkbookAuthDecision
import com.bm.backend.models.TeachWorkbookPrincipal
import com.bm.backend.services.TeachSkillActivationService
import com.bm.backend.services.AdminAccessControlService
import com.bm.backend.services.TeachMeAccessPolicy
import com.bm.backend.services.TeachClarificationAdapter
import com.bm.backend.services.TeachSupplierDraftAdapter
import com.bm.backend.services.TeachSupplierSkillSelectionService
import com.bm.backend.services.TeachProposalImportService
import com.bm.backend.services.TeachProposalValidationService
import com.bm.backend.services.TeachProductionPromotionService
import com.bm.backend.services.fixedTeachClarificationAdapter
import com.bm.backend.services.fixedTeachSupplierDraftAdapter
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.AuthErrorCode
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
    draftPreviewEnabled: Boolean = false,
    activationPilot: TeachSkillActivationService? = null,
    supplierSelection: TeachSupplierSkillSelectionService? = null,
    proposalImport: TeachProposalImportService? = null,
    proposalValidation: TeachProposalValidationService? = null,
    productionPromotion: TeachProductionPromotionService? = null,
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
    fun decide(token: String, validationWire: Boolean): TeachWorkbookAuthDecision {
        val user = try { verifyToken(token) } catch (failure: FirebaseAuthException) {
            if (validationWire && failure.authErrorCode !in setOf(AuthErrorCode.INVALID_ID_TOKEN, AuthErrorCode.EXPIRED_ID_TOKEN,
                    AuthErrorCode.REVOKED_ID_TOKEN, AuthErrorCode.USER_DISABLED, AuthErrorCode.USER_NOT_FOUND, AuthErrorCode.TENANT_ID_MISMATCH)) {
                throw TeachValidationIdentityUnavailable()
            }
            return TeachWorkbookAuthDecision.InvalidToken
        } catch (failure: IllegalArgumentException) {
            if (validationWire) return TeachWorkbookAuthDecision.InvalidToken
            throw failure
        } catch (failure: RuntimeException) {
            if (validationWire) throw TeachValidationIdentityUnavailable()
            throw failure
        }
        val authorized = if (!validationWire) adminAccess.isAdmin(user.email) else try {
            adminAccess.isAdminForProposalValidation(user.email)
        } catch (_: Exception) { throw TeachValidationIdentityUnavailable() }
        return when {
            !authorized -> TeachWorkbookAuthDecision.NotAdmin
            !policy.allows(user.email) -> TeachWorkbookAuthDecision.NotNominated
            else -> TeachWorkbookPrincipal(user.uid)
        }
    }
    teachWorkbookMessageRoutes(clarificationEnabled = clarificationEnabled, clarificationAdapter = clarificationAdapter,
        supplierDraftEnabled = supplierDraftEnabled, supplierDraftAdapter = supplierDraftAdapter,
        draftPreviewEnabled = draftPreviewEnabled, activationPilot = activationPilot, supplierSelection = supplierSelection,
        proposalImport = proposalImport, proposalValidation = proposalValidation, productionPromotion = productionPromotion,
        authorizeValidation = { decide(it, true) }) { decide(it, false) }
}
