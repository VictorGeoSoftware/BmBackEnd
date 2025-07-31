package com.bm.backend

import com.bm.backend.database.DatabaseFactory
import com.bm.backend.routes.priceTableRoutes
import com.bm.backend.services.PriceTableService
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.ratelimit.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.seconds

fun main() {
    embeddedServer(
        factory = Netty,
        port = 8080,
        host = "0.0.0.0",
        module = Application::module
    ).start(wait = true)
}

fun Application.configurePlugins() {
    // Install plugins
    install(ContentNegotiation) {
        json(Json {
            prettyPrint = true
            ignoreUnknownKeys = true
        })
    }

    install(StatusPages) {
        exception<Throwable> { call, cause ->
            call.respond(
                io.ktor.http.HttpStatusCode.InternalServerError,
                mapOf("error" to (cause.message ?: "Internal server error"))
            )
        }
    }

    install(RateLimit) {
        global {
            rateLimiter(limit = 100, refillPeriod = 60.seconds)
        }
    }
}

fun Application.configureRouting() {
    routing {
        get("/") {
            call.respond(mapOf("message" to "Price Table Backend Service is running"))
        }

        get("/health") {
            call.respond(mapOf("status" to "healthy", "timestamp" to java.time.Instant.now().toString()))
        }

        route("/api/v1") {
            priceTableRoutes(PriceTableService())
        }
    }
}


fun Application.module() {
    DatabaseFactory.init()
    this.configurePlugins()
    this.configureRouting()
}

fun Application.testModule() {
    this.configureRouting()
}