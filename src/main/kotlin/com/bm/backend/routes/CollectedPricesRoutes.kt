package com.bm.backend.routes

import com.bm.backend.models.CollectedPriceSubmitRequest
import com.bm.backend.models.ErrorResponse
import com.bm.backend.models.toDomainModel
import com.bm.backend.services.AdminAccessControlService
import com.bm.backend.services.CollectedPricesService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.application
import io.ktor.server.application.call
import io.ktor.server.application.log
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post

/**
 * Endpoints for collected prices — the customer's current electricity prices, as
 * entered by a broker on the app's current-conditions screen.
 *
 * - `POST /collected-prices` is called by the app; any authenticated account may
 *   submit, since submitting is a normal part of the broker workflow.
 * - `GET /admin/collected-prices` backs the BmWeb "Collected Prices" dashboard and
 *   is restricted to accounts on the admin allowlist.
 */
fun Route.collectedPricesRoutes(
    collectedPricesService: CollectedPricesService,
    adminAccessControlService: AdminAccessControlService
) {
    post("/collected-prices") {
        if (call.requireAuthenticatedFirebaseUser() == null) return@post

        try {
            val submission = call.receive<CollectedPriceSubmitRequest>().toDomainModel()

            when (val result = collectedPricesService.submit(submission)) {
                is CollectedPricesService.SubmitResult.Stored ->
                    call.respond(HttpStatusCode.Created)

                // Not an error the app should retry: 2.0TD is intentionally out of
                // scope, so acknowledge it rather than failing the broker's flow.
                is CollectedPricesService.SubmitResult.ExcludedTariff -> {
                    call.application.log.info(
                        "Collected prices skipped for excluded tariff: {}",
                        result.tariffType
                    )
                    call.respond(HttpStatusCode.NoContent)
                }
            }
        } catch (e: IllegalArgumentException) {
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse(message = e.message ?: "Invalid request")
            )
        } catch (e: Exception) {
            call.application.log.error("Error storing collected prices: ${e.message}", e)
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorResponse(message = "Internal server error: ${e.message}")
            )
        }
    }

    get("/admin/collected-prices") {
        if (call.requireAdminFirebaseUser(
                adminAccessControlService,
                "list collected prices"
            ) == null
        ) {
            return@get
        }

        try {
            val queryParameters = call.request.queryParameters
            call.respond(
                HttpStatusCode.OK,
                collectedPricesService.list(
                    limit = queryParameters["limit"]?.toIntOrNull()
                        ?: CollectedPricesService.DEFAULT_PAGE_SIZE,
                    offset = queryParameters["offset"]?.toIntOrNull() ?: 0,
                    tariffType = queryParameters["tariffType"],
                    companyName = queryParameters["companyName"]
                )
            )
        } catch (e: Exception) {
            call.application.log.error("Error listing collected prices: ${e.message}", e)
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorResponse(message = "Internal server error: ${e.message}")
            )
        }
    }
}
