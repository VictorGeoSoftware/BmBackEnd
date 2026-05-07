package com.bm.backend

import com.bm.backend.database.DatabaseFactory
import com.bm.backend.repositories.PostgresUserConsumptionRepository
import com.bm.backend.repositories.UserDataRepository
import com.bm.backend.repositories.UserActivityRepository
import com.bm.backend.routes.priceTableRoutes
import com.bm.backend.routes.userActivityRoutes
import com.bm.backend.routes.userConsumptionRoutes
import com.bm.backend.routes.userDataRoutes
import com.bm.backend.security.DataMigration
import com.bm.backend.security.EncryptionUtils
import com.bm.backend.services.ExternalApiService
import com.bm.backend.services.FirebasePriceUpdatesNotifier
import com.bm.backend.services.ComparatorReportPdfService
import com.bm.backend.services.PriceTableService
import com.bm.backend.services.UserConsumptionService
import com.bm.backend.services.UserDataService
import com.bm.backend.services.UserActivityService
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.metrics.micrometer.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.ratelimit.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.seconds

fun main(args: Array<String>) {
    io.ktor.server.netty.EngineMain.main(args)
}

fun Application.configurePlugins(): PrometheusMeterRegistry {
    val prometheusMeterRegistry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)

    // Install plugins
    install(ContentNegotiation) {
        json(Json {
            prettyPrint = true
            ignoreUnknownKeys = true
        })
    }

    install(MicrometerMetrics) {
        registry = prometheusMeterRegistry
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

    return prometheusMeterRegistry
}

fun Application.configureRouting(prometheusMeterRegistry: PrometheusMeterRegistry? = null) {
    // Initialize services
    val priceTableService = PriceTableService()
    val externalApiService = ExternalApiService()
    val priceUpdatesNotifier = FirebasePriceUpdatesNotifier()
    val userConsumptionRepository = PostgresUserConsumptionRepository()
    val userDataRepository = UserDataRepository()
    val userActivityRepository = UserActivityRepository()
    val jobService = com.bm.backend.services.JobService()
    val comparatorReportPdfService = ComparatorReportPdfService()
    val userConsumptionService = UserConsumptionService(
        userConsumptionRepository,
        externalApiService,
        priceTableService
    )
    val userDataService = UserDataService(userDataRepository)
    val userActivityService = UserActivityService(userActivityRepository)
    
    routing {
        get("/") {
            call.respond(mapOf("message" to "Price Table Backend Service is running"))
        }

        get("/metrics") {
            if (prometheusMeterRegistry != null) {
                call.respondText(prometheusMeterRegistry.scrape())
            } else {
                call.respond(io.ktor.http.HttpStatusCode.NotFound, "Metrics not configured")
            }
        }

        get("/health") {
            val dbHealthy = try {
                org.jetbrains.exposed.sql.transactions.transaction {
                    exec("SELECT 1") { it.next() }
                }
                true
            } catch (_: Exception) {
                false
            }
            val status = if (dbHealthy) "healthy" else "degraded"
            val httpStatus = if (dbHealthy) io.ktor.http.HttpStatusCode.OK
                else io.ktor.http.HttpStatusCode.ServiceUnavailable
            call.respond(httpStatus, mapOf(
                "status" to status,
                "database" to if (dbHealthy) "connected" else "unreachable",
                "timestamp" to java.time.Instant.now().toString()
            ))
        }

        route("/api/v1") {
            priceTableRoutes(priceTableService, externalApiService, priceUpdatesNotifier)
            userConsumptionRoutes(userConsumptionService, jobService, comparatorReportPdfService)
            userDataRoutes(userDataService)
            userActivityRoutes(userActivityService)
        }
    }
}


fun Application.module() {
    initEncryption()
    DatabaseFactory.init()
    DataMigration.encryptExistingUserData()
    val prometheusMeterRegistry = this.configurePlugins()
    this.configureRouting(prometheusMeterRegistry)
}

private fun Application.initEncryption() {
    val encryptionKey = System.getenv("BM_ENCRYPTION_KEY")
    if (encryptionKey.isNullOrBlank()) {
        log.warn("BM_ENCRYPTION_KEY not set. Generating a temporary key for development. DO NOT use in production!")
        val tempKey = EncryptionUtils.generateKey()
        log.warn("Generated temporary encryption key (store this in BM_ENCRYPTION_KEY): {}", tempKey)
        EncryptionUtils.init(tempKey)
    } else {
        EncryptionUtils.init(encryptionKey)
        log.info("Encryption initialized with provided key")
    }
}
