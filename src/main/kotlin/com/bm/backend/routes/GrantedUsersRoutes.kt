package com.bm.backend.routes

import com.bm.backend.models.ErrorResponse
import com.bm.backend.models.GrantedUserAddRequest
import com.bm.backend.models.GrantedUserListResponse
import com.bm.backend.models.GrantedUserMutationResponse
import com.bm.backend.services.AdminAuthService
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
 * Administrative endpoints for managing the granted-users allowlist, guarded
 * by [AdminAuthService] (shared-secret header). Consumed by the BmWeb
 * "Usuarios" dashboard.
 */
fun Route.grantedUsersRoutes(
    grantedUsersService: GrantedUsersService,
    adminAuthService: AdminAuthService
) {
    get("/admin/granted-users") {
        if (!call.requireAdmin(adminAuthService, "list granted users")) return@get

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
        if (!call.requireAdmin(adminAuthService, "add granted user")) return@post

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
        if (!call.requireAdmin(adminAuthService, "delete granted user")) return@delete

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
 * Verifies the admin shared secret, responding and returning false when the
 * request is not authorized. Mirrors the guard used by [adminRoutes].
 */
private suspend fun ApplicationCall.requireAdmin(
    adminAuthService: AdminAuthService,
    action: String
): Boolean {
    if (!adminAuthService.enabled) {
        respond(
            HttpStatusCode.ServiceUnavailable,
            ErrorResponse(message = "Admin API is not configured")
        )
        return false
    }

    val providedToken = request.headers[AdminAuthService.HEADER]
    if (!adminAuthService.isAuthorized(providedToken)) {
        application.log.warn("AUDIT: Unauthorized admin attempt to {}", action)
        respond(
            HttpStatusCode.Unauthorized,
            ErrorResponse(message = "Invalid or missing admin token")
        )
        return false
    }
    return true
}
