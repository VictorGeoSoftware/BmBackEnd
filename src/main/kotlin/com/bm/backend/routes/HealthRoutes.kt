package com.bm.backend.routes

import com.bm.backend.models.LivenessResponse
import com.bm.backend.models.ReadinessResponse
import com.bm.backend.services.HealthService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import java.time.Instant

/**
 * Health probes.
 *
 * - `GET /health/live` — liveness. Never touches a dependency, so it fails only
 *   if the process itself is broken. This is what Docker healthchecks target.
 * - `GET /health/ready` — readiness. 200 when the database is reachable,
 *   503 otherwise.
 * - `GET /health` — retained as an alias of `/health/ready` so existing deploy
 *   workflows, monitoring and scripts keep working unchanged.
 */
fun Route.healthRoutes(healthService: HealthService) {

    get("/health") {
        call.respondReadiness(healthService)
    }

    get("/health/live") {
        val live = healthService.isLive()
        call.respond(
            if (live) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable,
            LivenessResponse(
                status = if (live) "alive" else "dead",
                timestamp = Instant.now().toString()
            )
        )
    }

    get("/health/ready") {
        call.respondReadiness(healthService)
    }
}

private suspend fun io.ktor.server.application.ApplicationCall.respondReadiness(
    healthService: HealthService
) {
    val ready = healthService.isReady()
    respond(
        if (ready) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable,
        ReadinessResponse(
            status = if (ready) "healthy" else "degraded",
            database = if (ready) "connected" else "unreachable",
            timestamp = Instant.now().toString()
        )
    )
}
