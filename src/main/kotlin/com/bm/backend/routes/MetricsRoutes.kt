package com.bm.backend.routes

import com.bm.backend.services.MetricsAuthService
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry

/**
 * Prometheus scrape endpoint.
 *
 * Deliberately registered outside the rate-limited route group: the limiter is
 * shared across all callers, so a scraper polling every 15s would consume the
 * same budget as real traffic and could contribute to 429s on the API.
 */
fun Route.metricsRoutes(
    metricsAuthService: MetricsAuthService,
    prometheusMeterRegistry: PrometheusMeterRegistry?
) {
    get("/metrics") {
        if (!metricsAuthService.isAuthorized(call.request.header(HttpHeaders.Authorization))) {
            call.respond(HttpStatusCode.Unauthorized)
            return@get
        }

        if (prometheusMeterRegistry == null) {
            call.respond(HttpStatusCode.NotFound, "Metrics not configured")
            return@get
        }

        call.respondText(prometheusMeterRegistry.scrape())
    }
}
