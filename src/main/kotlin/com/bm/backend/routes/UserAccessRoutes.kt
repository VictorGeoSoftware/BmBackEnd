package com.bm.backend.routes

import com.bm.backend.models.UserAccessResponse
import com.bm.backend.services.AccessControlService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

fun Route.userAccessRoutes(
    accessControlService: AccessControlService,
    verifyToken: suspend (String) -> AuthenticatedFirebaseUser = ::verifyFirebaseIdToken
) {
    get("/me/access") {
        val user = call.requireGrantedFirebaseUser(accessControlService, verifyToken) ?: return@get
        call.respond(
            HttpStatusCode.OK,
            UserAccessResponse(
                tier = user.tier,
                capabilities = user.capabilities.sortedBy { it.name }
            )
        )
    }
}
