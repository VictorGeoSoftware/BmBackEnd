package com.bm.backend.routes

import com.bm.backend.models.ErrorResponse
import com.bm.backend.models.UserConsumption
import com.bm.backend.services.UserConsumptionService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*

fun Route.userConsumptionRoutes(userConsumptionService: UserConsumptionService) {
    post("/consumption-report") {
        try {
            val consumptionReport = call.receive<UserConsumption>()
            userConsumptionService.processConsumptionReport(consumptionReport)

            call.respond(HttpStatusCode.OK)
        } catch (e: Exception) {
            call.application.log.error("Error processing consumption report: ${e.message}", e)
            e.printStack()
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorResponse(message = "Internal server error: ${e.message}")
            )
        }
    }
}
