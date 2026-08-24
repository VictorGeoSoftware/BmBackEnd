package com.bm.backend

import com.bm.backend.database.DatabaseFactory
import com.bm.backend.models.ErrorResponse
import com.bm.backend.observability.DeploymentInfo
import com.bm.backend.plugins.REQUEST_ID_MDC_KEY
import com.bm.backend.plugins.RequestIdPlugin
import com.bm.backend.plugins.requestId
import com.bm.backend.repositories.AdminUsersRepository
import com.bm.backend.repositories.CollectedPricesRepository
import com.bm.backend.repositories.ExposedTransactionRunner
import com.bm.backend.repositories.GrantedUsersRepository
import com.bm.backend.repositories.PostgresUserConsumptionRepository
import com.bm.backend.repositories.UserDataRepository
import com.bm.backend.repositories.UserActivityRepository
import com.bm.backend.routes.priceTableRoutes
import com.bm.backend.routes.adminAccessRoutes
import com.bm.backend.routes.adminRoutes
import com.bm.backend.routes.authRoutes
import com.bm.backend.routes.collectedPricesRoutes
import com.bm.backend.routes.grantedUsersRoutes
import com.bm.backend.routes.userActivityRoutes
import com.bm.backend.routes.userConsumptionRoutes
import com.bm.backend.routes.userDataRoutes
import com.bm.backend.security.DataMigration
import com.bm.backend.security.EncryptionUtils
import com.bm.backend.services.AccessControlService
import com.bm.backend.services.AdminAccessControlService
import com.bm.backend.services.AdminAuthService
import com.bm.backend.services.CollectedPricesService
import com.bm.backend.services.ExternalApiService
import com.bm.backend.services.FirebaseForceLogoutNotifier
import com.bm.backend.services.FirebasePriceUpdatesNotifier
import com.bm.backend.services.FirebaseUserAccountRevoker
import com.bm.backend.services.ComparatorReportPdfService
import com.bm.backend.services.GrantedUsersService
import com.bm.backend.services.PriceTableService
import com.bm.backend.services.UserConsumptionService
import com.bm.backend.services.UserDataService
import com.bm.backend.services.UserActivityService
import com.bm.backend.services.ValidationException
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.metrics.micrometer.*
import io.ktor.server.plugins.callloging.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.ratelimit.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import kotlinx.serialization.json.Json
import org.slf4j.event.Level
import kotlin.time.Duration.Companion.seconds

fun main(args: Array<String>) {
    io.ktor.server.netty.EngineMain.main(args)
}

fun Application.configurePlugins(): PrometheusMeterRegistry {
    val prometheusMeterRegistry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
    // Tag every metric with the environment so one Prometheus/Grafana can serve
    // both backend-prod and backend-qa without mixing their series.
    prometheusMeterRegistry.config().commonTags(
        "env", DeploymentInfo.environment,
        "service", DeploymentInfo.serviceName,
    )

    // Install plugins
    install(ContentNegotiation) {
        json(Json {
            prettyPrint = true
            ignoreUnknownKeys = true
        })
    }

    // Must be installed before CallLogging so the id exists when the MDC is built.
    install(RequestIdPlugin)

    install(CallLogging) {
        level = Level.INFO
        // Exposed to every log statement made while handling the call.
        mdc(REQUEST_ID_MDC_KEY) { it.requestId }
        mdc("method") { it.request.httpMethod.value }
        mdc("path") { it.request.path() }
        // Ktor's default formatter colourises the status and method with ANSI
        // escape codes. Harmless on a dev terminal, but with LOG_FORMAT=json the
        // raw escapes are embedded in the JSON "message" field, which renders as
        // garbage in Grafana and breaks exact-match Loki queries. Format plainly.
        format { call ->
            val status = call.response.status()?.value?.toString() ?: "unhandled"
            "${call.request.httpMethod.value} ${call.request.path()} -> $status"
        }
        // Health and metrics are polled continuously by Docker and Prometheus;
        // logging them would drown the real traffic in Loki.
        filter { call: ApplicationCall ->
            val path = call.request.path()
            !path.startsWith("/health") && path != "/metrics"
        }
    }

    install(MicrometerMetrics) {
        registry = prometheusMeterRegistry
    }

    install(StatusPages) {
        // Validation failures are expected, caller-fixable and safe to echo back.
        exception<ValidationException> { call, cause ->
            call.application.log.warn("Validation failed: {}", cause.message)
            call.respond(
                io.ktor.http.HttpStatusCode.BadRequest,
                ErrorResponse(
                    message = cause.message ?: "Invalid request",
                    requestId = call.requestId,
                )
            )
        }
        // Last-resort handler. Anything reaching here is a bug: log it with the
        // stack trace (previously it was silently swallowed) and return a generic
        // message, since `cause.message` can leak SQL fragments and file paths.
        exception<Throwable> { call, cause ->
            call.application.log.error(
                "Unhandled exception while processing {} {}",
                call.request.httpMethod.value,
                call.request.path(),
                cause,
            )
            call.respond(
                io.ktor.http.HttpStatusCode.InternalServerError,
                ErrorResponse(
                    message = "Internal server error",
                    requestId = call.requestId,
                )
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
    val grantedUsersRepository = GrantedUsersRepository()
    val accessControlService = AccessControlService(grantedUsersRepository)
    val adminUsersRepository = AdminUsersRepository()
    val adminAccessControlService = AdminAccessControlService(adminUsersRepository)
    val adminAuthService = AdminAuthService.fromEnv()
    val grantedUsersService = GrantedUsersService(
        grantedUsersRepository = grantedUsersRepository,
        userDataRepository = userDataRepository,
        userActivityRepository = userActivityRepository,
        userConsumptionRepository = userConsumptionRepository,
        userAccountRevoker = FirebaseUserAccountRevoker(),
        forceLogoutNotifier = FirebaseForceLogoutNotifier(),
        transactionRunner = ExposedTransactionRunner()
    )
    val collectedPricesRepository = CollectedPricesRepository()
    val collectedPricesService = CollectedPricesService(collectedPricesRepository)
    
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
            userDataRoutes(userDataService, accessControlService)
            userActivityRoutes(userActivityService)
            authRoutes(userActivityService)
            adminRoutes(userDataService, adminAuthService)
            adminAccessRoutes(adminAccessControlService)
            grantedUsersRoutes(grantedUsersService, adminAccessControlService)
            collectedPricesRoutes(collectedPricesService, adminAccessControlService)
        }
    }
}


fun Application.module() {
    log.info(
        "Starting {} (env={}, logFormat={}, logLevel={})",
        DeploymentInfo.serviceName,
        DeploymentInfo.environment,
        System.getenv("LOG_FORMAT") ?: "text",
        System.getenv("LOG_LEVEL") ?: "INFO",
    )
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
