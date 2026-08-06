package com.bm.backend.routes

import com.bm.backend.models.ErrorResponse
import com.bm.backend.models.GrantedUserAddRequest
import com.bm.backend.models.GrantedUserListResponse
import com.bm.backend.models.GrantedUserMutationResponse
import com.bm.backend.services.AccessControlService
import com.bm.backend.services.GrantedUsersService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.application
import io.ktor.server.application.call
import io.ktor.server.application.log
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post

/**
 * Endpoints for managing the granted-users allowlist. Consumed by the BmWeb
 * "Usuarios" dashboard.
 *
 * Authorization: the caller must present a valid Firebase ID token whose
 * account is itself granted (its email exists in `granted_users`). Any granted
 * user can manage grants; ungranted callers are rejected with 403, mirroring
 * the login policy.
 */
fun Route.grantedUsersRoutes(
    grantedUsersService: GrantedUsersService,
    accessControlService: AccessControlService
) {
    get("/admin/granted-users") {
        if (!call.requireGrantedFirebaseUser(accessControlService, "list granted users")) return@get

        try {
            call.respond(
                HttpStatusCode.OK,
                GrantedUserListResponse(
                    success = true,
                    users = grantedUsersService.listGrants()
                )
            )
        } catch (e: Exception) {
            call.application.log.error("Error listing granted users: ${e.message}", e)
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorResponse(message = "Internal server error: ${e.message}")
            )
        }
    }

    post("/admin/granted-users") {
        if (!call.requireGrantedFirebaseUser(accessControlService, "add granted user")) return@post

        try {
            val request = call.receive<GrantedUserAddRequest>()
            when (val result = grantedUsersService.addGrant(request.email)) {
                is GrantedUsersService.AddGrantResult.Added -> call.respond(
                    HttpStatusCode.Created,
                    GrantedUserMutationResponse(
                        success = true,
                        email = result.email,
                        message = "Access granted"
                    )
                )
                is GrantedUsersService.AddGrantResult.AlreadyExists -> call.respond(
                    HttpStatusCode.Conflict,
                    GrantedUserMutationResponse(
                        success = false,
                        email = result.email,
                        message = "Access already granted for this email"
                    )
                )
                GrantedUsersService.AddGrantResult.InvalidEmail -> call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse(message = "A valid email is required")
                )
            }
        } catch (e: Exception) {
            call.application.log.error("Error adding granted user: ${e.message}", e)
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorResponse(message = "Internal server error: ${e.message}")
            )
        }
    }

    delete("/admin/granted-users/{email}") {
        if (!call.requireGrantedFirebaseUser(accessControlService, "delete granted user")) return@delete

        try {
            when (val result = grantedUsersService.deleteGrant(call.parameters["email"])) {
                is GrantedUsersService.DeleteGrantResult.Deleted -> call.respond(
                    HttpStatusCode.OK,
                    GrantedUserMutationResponse(
                        success = true,
                        email = result.email,
                        message = "Grant and all user data deleted"
                    )
                )
                is GrantedUsersService.DeleteGrantResult.NotFound -> call.respond(
                    HttpStatusCode.NotFound,
                    GrantedUserMutationResponse(
                        success = false,
                        email = result.email,
                        message = "No grant found for this email"
                    )
                )
                GrantedUsersService.DeleteGrantResult.InvalidEmail -> call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse(message = "A valid email is required")
                )
            }
        } catch (e: Exception) {
            call.application.log.error("Error deleting granted user: ${e.message}", e)
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorResponse(message = "Internal server error: ${e.message}")
            )
        }
    }
}

/**
 * Requires a Firebase-authenticated caller whose account is granted access.
 * Responds and returns false when the request is not authorized.
 */
private suspend fun ApplicationCall.requireGrantedFirebaseUser(
    accessControlService: AccessControlService,
    action: String
): Boolean {
    val authenticatedUser = requireAuthenticatedFirebaseUser() ?: return false

    if (!accessControlService.isEmailAllowed(authenticatedUser.email)) {
        application.log.warn(
            "AUDIT: Non-granted account attempted to {} uid={} email={}",
            action,
            authenticatedUser.uid,
            authenticatedUser.email
        )
        respond(
            HttpStatusCode.Forbidden,
            ErrorResponse(message = "Esta cuenta no está autorizada para gestionar usuarios.")
        )
        return false
    }
    return true
}
