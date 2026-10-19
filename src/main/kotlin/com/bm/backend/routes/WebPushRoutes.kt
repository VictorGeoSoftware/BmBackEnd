package com.bm.backend.routes

import com.bm.backend.models.ErrorResponse
import com.bm.backend.models.WebPushTokenDeleteRequest
import com.bm.backend.models.WebPushTokenMutationResponse
import com.bm.backend.models.WebPushTokenRegisterRequest
import com.bm.backend.services.AccessControlService
import com.bm.backend.services.WebPushTokensService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.application.log
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.post

fun Route.webPushRoutes(
    webPushTokensService: WebPushTokensService,
    accessControlService: AccessControlService,
    verifyToken: suspend (String) -> AuthenticatedFirebaseUser = ::verifyFirebaseIdToken
) {
    post("/push/web-tokens") {
        try {
            val user = call.requireGrantedFirebaseUser(accessControlService, verifyToken) ?: return@post
            val request = call.receive<WebPushTokenRegisterRequest>()
            webPushTokensService.register(
                rawToken = request.token,
                rawPlatform = request.platform,
                uid = user.uid,
                email = user.email
            )
            call.respond(HttpStatusCode.OK, WebPushTokenMutationResponse(success = true))
        } catch (e: IllegalArgumentException) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(message = e.message ?: "Invalid request"))
        } catch (e: BadRequestException) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(message = "Invalid request"))
        } catch (e: Exception) {
            call.application.log.error("Error registering web push token: ${e.message}", e)
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse(message = "Internal server error"))
        }
    }

    delete("/push/web-tokens") {
        try {
            val user = call.requireGrantedFirebaseUser(accessControlService, verifyToken) ?: return@delete
            val request = call.receive<WebPushTokenDeleteRequest>()
            webPushTokensService.unregister(rawToken = request.token, uid = user.uid)
            call.respond(HttpStatusCode.OK, WebPushTokenMutationResponse(success = true))
        } catch (e: IllegalArgumentException) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(message = e.message ?: "Invalid request"))
        } catch (e: BadRequestException) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(message = "Invalid request"))
        } catch (e: Exception) {
            call.application.log.error("Error deleting web push token: ${e.message}", e)
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse(message = "Internal server error"))
        }
    }
}
