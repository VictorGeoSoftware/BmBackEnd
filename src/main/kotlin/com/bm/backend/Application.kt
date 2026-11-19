package com.bm.backend

import com.bm.backend.database.DatabaseFactory
import com.bm.backend.models.ErrorResponse
import com.bm.backend.observability.DeploymentInfo
import com.bm.backend.plugins.REQUEST_ID_MDC_KEY
import com.bm.backend.plugins.RequestIdPlugin
import com.bm.backend.plugins.RateLimitKeys
import com.bm.backend.plugins.requestId
import com.bm.backend.repositories.AdminUsersRepository
import com.bm.backend.repositories.CollectedPricesRepository
import com.bm.backend.repositories.ExposedTransactionRunner
import com.bm.backend.repositories.ExposedDatabaseHealthCheck
import com.bm.backend.repositories.GrantedUsersRepository
import com.bm.backend.repositories.PostgresUserConsumptionRepository
import com.bm.backend.repositories.UserDataRepository
import com.bm.backend.repositories.UserActivityRepository
import com.bm.backend.repositories.WebPushTokensRepository
import com.bm.backend.routes.priceTableRoutes
import com.bm.backend.routes.adminAccessRoutes
import com.bm.backend.routes.teachMeAccessRoutes
import com.bm.backend.routes.isTeachUpload
import com.bm.backend.services.TeachMeAccessPolicy
import com.bm.backend.routes.authRoutes
import com.bm.backend.routes.collectedPricesRoutes
import com.bm.backend.routes.grantedUsersRoutes
import com.bm.backend.routes.healthRoutes
import com.bm.backend.routes.premiumReportRoutes
import com.bm.backend.routes.metricsRoutes
import com.bm.backend.routes.userActivityRoutes
import com.bm.backend.routes.userAccessRoutes
import com.bm.backend.routes.userConsumptionRoutes
import com.bm.backend.routes.userDataRoutes
import com.bm.backend.routes.webPushRoutes
import com.bm.backend.security.DataMigration
import com.bm.backend.security.EncryptionUtils
import com.bm.backend.services.AccessControlService
import com.bm.backend.services.AdminAccessControlService
import com.bm.backend.services.CollectedPricesService
import com.bm.backend.services.ExternalApiService
import com.bm.backend.services.FirebaseForceLogoutNotifier
import com.bm.backend.services.CompositePriceUpdatesNotifier
import com.bm.backend.services.DebouncingPriceUpdatesNotifier
import com.bm.backend.services.FirebasePriceUpdatesNotifier
import com.bm.backend.services.FirebaseWebPushSender
import com.bm.backend.services.WebPushPriceUpdatesNotifier
import com.bm.backend.services.FirebaseUserAccountRevoker
import com.bm.backend.services.WebPushTokensService
import com.bm.backend.services.ComparatorReportPdfService
import com.bm.backend.services.GrantedUsersService
import com.bm.backend.services.HealthService
import com.bm.backend.services.InternalApiAuthService
import com.bm.backend.services.MetricsAuthService
import com.bm.backend.services.PremiumReportService
import com.bm.backend.services.premiumreport.PdfPremiumReportRenderer
import com.bm.backend.services.PriceTableService
import com.bm.backend.services.ReportDatasetService
import com.bm.backend.services.UserConsumptionService
import com.bm.backend.services.UserDataService
import com.bm.backend.services.UserActivityService
import com.bm.backend.services.ValidationException
import io.ktor.http.HttpHeaders
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
import java.time.Clock

fun main(args: Array<String>) {
    io.ktor.server.netty.EngineMain.main(args)
}

/** Per-caller limiter for the `/api/v1` route group (key: [RateLimitKeys.callerKey]). */
val API_RATE_LIMIT = RateLimitName("api")

/** Whole-backend backstop around [API_RATE_LIMIT]; bounds callers that rotate keys. */
val API_GLOBAL_RATE_LIMIT = RateLimitName("api-global")

/** Requests per minute per caller. PROD peak for the whole backend was 33/min (Nov 2026). */
const val API_RATE_LIMIT_PER_CALLER = 120

/** Requests per minute for the whole backend (~18x the observed PROD peak). */
const val API_RATE_LIMIT_GLOBAL = 600

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

    // Cover even unmatched (non-QA) and rate-limited upload requests, without mounting a non-QA route.
    intercept(ApplicationCallPipeline.Plugins) {
        if (call.isTeachUpload()) call.response.header(HttpHeaders.CacheControl, "no-store")
    }

    install(StatusPages) {
        // Validation failures are expected, caller-fixable and safe to echo back.
        exception<ValidationException> { call, cause ->
            if (call.isTeachUpload()) {
                call.respond(io.ktor.http.HttpStatusCode.BadRequest, ErrorResponse(message = "Invalid request", requestId = call.requestId))
                return@exception
            }
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
            if (call.isTeachUpload()) {
                // Third-party parse/IO errors can contain user-controlled metadata. Never log the exception here.
                call.application.log.error("Unhandled Enséñame upload failure")
            } else {
                call.application.log.error(
                    "Unhandled exception while processing {} {}",
                    call.request.httpMethod.value,
                    call.request.path(),
                    cause,
                )
            }
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
        // Scoped to the API routes rather than `global { }`. A global limiter
        // also covers `/`, `/health*` and `/metrics`, which means continuous
        // infrastructure polling (Docker healthchecks every 30s, and a
        // Prometheus scrape every 15s once Phase 2 lands) spends the same
        // budget as real user traffic — and a 429 on a healthcheck would
        // restart a healthy container.
        //
        // The limit and window are unchanged; only the set of routes it
        // applies to has narrowed.
        //
        // Per caller (TECH_DEBT #19, fixed 2026-11-18). Previously no
        // `requestKey` was set, so Ktor's default `Unit` key made this ONE
        // 100 req/min bucket for the whole backend: at 20 concurrent users each
        // got ~5 req/min, and one client could block everyone.
        //
        // Key = hash of the bearer token (one Firebase session), else the
        // client IP; see RateLimitKeys. Not the IP for authenticated users:
        // web traffic arrives from Vercel's servers, so IPs say little about
        // who the user is.
        //
        // Sizing: polling alone is up to ~35 req/min for a user with PowerAppWeb
        // (study status every 3s) and BmWeb's users page (every 4s) open; PROD
        // peaked at 33 req/min for ALL callers combined. The global backstop
        // only exists to bound floods of invented tokens.
        register(API_RATE_LIMIT) {
            rateLimiter(limit = API_RATE_LIMIT_PER_CALLER, refillPeriod = 60.seconds)
            requestKey { call -> RateLimitKeys.callerKey(call) }
        }
        register(API_GLOBAL_RATE_LIMIT) {
            rateLimiter(limit = API_RATE_LIMIT_GLOBAL, refillPeriod = 60.seconds)
        }
    }

    return prometheusMeterRegistry
}

fun Application.configureRouting(
    prometheusMeterRegistry: PrometheusMeterRegistry? = null,
    internalApiAuthService: InternalApiAuthService = InternalApiAuthService.fromEnv()
) {
    // Initialize services
    val priceTableService = PriceTableService()
    val externalApiService = ExternalApiService()
    val webPushTokensRepository = WebPushTokensRepository()
    val priceUpdatesNotifier = DebouncingPriceUpdatesNotifier(
        CompositePriceUpdatesNotifier(
            listOf(
                FirebasePriceUpdatesNotifier(),
                WebPushPriceUpdatesNotifier(webPushTokensRepository, FirebaseWebPushSender()),
            )
        )
    )
    val userConsumptionRepository = PostgresUserConsumptionRepository()
    val userDataRepository = UserDataRepository()
    val userActivityRepository = UserActivityRepository()
    val jobService = com.bm.backend.services.JobService()
    val comparatorReportPdfService = ComparatorReportPdfService()
    // Assembled Premium PDF (spec report-document): in-memory, from the stored study only.
    val premiumReportService = PremiumReportService(
        ReportDatasetService(jobService),
        PdfPremiumReportRenderer(Clock.systemUTC())
    )
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
    val webPushTokensService = WebPushTokensService(webPushTokensRepository)
    val grantedUsersService = GrantedUsersService(
        grantedUsersRepository = grantedUsersRepository,
        userDataRepository = userDataRepository,
        userActivityRepository = userActivityRepository,
        userConsumptionRepository = userConsumptionRepository,
        webPushTokensRepository = webPushTokensRepository,
        userAccountRevoker = FirebaseUserAccountRevoker(),
        forceLogoutNotifier = FirebaseForceLogoutNotifier(),
        transactionRunner = ExposedTransactionRunner()
    )
    val collectedPricesRepository = CollectedPricesRepository()
    val collectedPricesService = CollectedPricesService(collectedPricesRepository)
    val healthService = HealthService(ExposedDatabaseHealthCheck())
    val metricsAuthService = MetricsAuthService.fromEnv()
    
    routing {
        get("/") {
            call.respond(mapOf("message" to "Price Table Backend Service is running"))
        }

        metricsRoutes(metricsAuthService, prometheusMeterRegistry)

        healthRoutes(healthService)

        rateLimit(API_GLOBAL_RATE_LIMIT) {
            rateLimit(API_RATE_LIMIT) {
                route("/api/v1") {
                    priceTableRoutes(
                        priceTableService,
                        externalApiService,
                        priceUpdatesNotifier,
                        accessControlService,
                        adminAccessControlService,
                        internalApiAuthService
                    )
                    userConsumptionRoutes(
                        userConsumptionService,
                        jobService,
                        comparatorReportPdfService,
                        accessControlService,
                        internalApiAuthService = internalApiAuthService
                    )
                    premiumReportRoutes(premiumReportService, accessControlService)
                    userDataRoutes(userDataService, accessControlService)
                    userAccessRoutes(accessControlService)
                    webPushRoutes(webPushTokensService, accessControlService)
                    userActivityRoutes(
                        userActivityService,
                        accessControlService,
                        adminAccessControlService
                    )
                    authRoutes(userActivityService, accessControlService)
                    adminAccessRoutes(adminAccessControlService)
                    teachMeAccessRoutes(
                        adminAccessControlService,
                        TeachMeAccessPolicy(DeploymentInfo.environment, System.getenv("BM_TEACH_ME_ADMIN_EMAILS")),
                        clarificationEnabled = System.getenv("BM_TEACH_CLARIFY_LOCAL") == "true",
                        supplierDraftEnabled = System.getenv("BM_TEACH_DRAFT_LOCAL") == "true",
                        draftPreviewEnabled = System.getenv("BM_TEACH_PREVIEW_LOCAL") == "true",
                    )
                    grantedUsersRoutes(grantedUsersService, adminAccessControlService)
                    collectedPricesRoutes(
                        collectedPricesService,
                        accessControlService,
                        adminAccessControlService
                    )
                }
            }
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
