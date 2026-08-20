package com.bm.backend

import com.bm.backend.models.ErrorResponse
import com.bm.backend.plugins.REQUEST_ID_HEADER
import com.bm.backend.plugins.REQUEST_ID_MDC_KEY
import com.bm.backend.services.ValidationException
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import org.slf4j.MDC
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Covers the Phase 0 observability foundations: request-id correlation and the
 * global error handler. Deliberately avoids [configureRouting] so these run
 * without Docker/Postgres.
 */
class ObservabilityTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun ApplicationTestBuilder.observabilityTestApp(routes: Routing.() -> Unit) {
        environment { config = io.ktor.server.config.MapApplicationConfig() }
        application {
            configurePlugins()
            routing { routes() }
        }
    }

    // ---------- request id ----------

    @Test
    fun `generates a request id when the caller does not supply one`() = testApplication {
        observabilityTestApp {
            get("/ping") { call.respondText("pong") }
        }

        val response = client.get("/ping")

        val id = response.headers[REQUEST_ID_HEADER]
        assertNotNull(id, "response must echo a correlation id")
        assertTrue(id.isNotBlank())
    }

    @Test
    fun `reuses the caller supplied request id`() = testApplication {
        observabilityTestApp {
            get("/ping") { call.respondText("pong") }
        }

        val response = client.get("/ping") { header(REQUEST_ID_HEADER, "upstream-abc-123") }

        assertEquals("upstream-abc-123", response.headers[REQUEST_ID_HEADER])
    }

    @Test
    fun `rejects a forged request id containing unsafe characters`() = testApplication {
        observabilityTestApp {
            get("/ping") { call.respondText("pong") }
        }

        // Unsafe characters (whitespace, newlines) would let a caller forge
        // extra log records, so the value must be discarded and replaced.
        val response = client.get("/ping") { header(REQUEST_ID_HEADER, "abc def") }

        val id = response.headers[REQUEST_ID_HEADER]
        assertNotNull(id)
        assertNotEquals("abc def", id)
        assertTrue(id.all { it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' })
    }

    @Test
    fun `rejects an over long request id`() = testApplication {
        observabilityTestApp {
            get("/ping") { call.respondText("pong") }
        }

        val oversized = "a".repeat(500)
        val response = client.get("/ping") { header(REQUEST_ID_HEADER, oversized) }

        assertFalse(response.headers[REQUEST_ID_HEADER] == oversized)
    }

    @Test
    fun `exposes the request id to log statements via MDC`() = testApplication {
        observabilityTestApp {
            get("/mdc") { call.respondText(MDC.get(REQUEST_ID_MDC_KEY) ?: "absent") }
        }

        val response = client.get("/mdc") { header(REQUEST_ID_HEADER, "mdc-check-1") }

        assertEquals("mdc-check-1", response.bodyAsText())
    }

    @Test
    fun `metrics are tagged with the deployment environment`() = testApplication {
        lateinit var registry: io.micrometer.prometheusmetrics.PrometheusMeterRegistry
        environment { config = io.ktor.server.config.MapApplicationConfig() }
        application {
            registry = configurePlugins()
            routing { get("/ping") { call.respondText("pong") } }
        }

        client.get("/ping")

        // Prometheus/Grafana rely on these labels to tell backend-prod from
        // backend-qa while sharing a single monitoring stack.
        val scrape = registry.scrape()
        assertTrue(scrape.contains("""env="""" + com.bm.backend.observability.DeploymentInfo.environment + '"'),
            "every metric must carry the env label; scrape was:\n${scrape.take(600)}")
        assertTrue(scrape.contains("""service="bm-backend""""),
            "every metric must carry the service label")
    }

    // ---------- error handling ----------

    @Test
    fun `unhandled exceptions return a generic message and do not leak internals`() = testApplication {
        observabilityTestApp {
            get("/boom") { throw IllegalStateException("jdbc:postgresql://secret-host/bm_backend password=hunter2") }
        }

        val response = client.get("/boom")

        assertEquals(HttpStatusCode.InternalServerError, response.status)
        val body = response.bodyAsText()
        assertFalse(body.contains("secret-host"), "internal details must not reach the client")
        assertFalse(body.contains("hunter2"), "internal details must not reach the client")

        val error = json.decodeFromString<ErrorResponse>(body)
        assertEquals("Internal server error", error.message)
        assertNotNull(error.requestId, "clients need the id to report the failure")
    }

    @Test
    fun `unhandled exception response carries the same request id as the header`() = testApplication {
        observabilityTestApp {
            get("/boom") { throw IllegalStateException("nope") }
        }

        val response = client.get("/boom") { header(REQUEST_ID_HEADER, "trace-me-42") }

        val error = json.decodeFromString<ErrorResponse>(response.bodyAsText())
        assertEquals("trace-me-42", error.requestId)
        assertEquals("trace-me-42", response.headers[REQUEST_ID_HEADER])
    }

    @Test
    fun `validation failures return 400 with the caller facing message`() = testApplication {
        observabilityTestApp {
            get("/invalid") { throw ValidationException("iva must be greater than or equal to 0") }
        }

        val response = client.get("/invalid")

        assertEquals(HttpStatusCode.BadRequest, response.status)
        val error = json.decodeFromString<ErrorResponse>(response.bodyAsText())
        assertEquals("iva must be greater than or equal to 0", error.message)
        assertFalse(error.success)
    }

    // ---------- log message hygiene ----------

    /**
     * Ktor's default CallLogging formatter colourises output with ANSI escape
     * codes. Under LOG_FORMAT=json those escapes end up inside the JSON
     * "message" field, rendering as garbage in Grafana and breaking exact-match
     * Loki queries. Access log lines must stay plain text.
     */
    @Test
    fun `access log lines contain no ANSI escape codes`() = testApplication {
        val logger = org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)
            as ch.qos.logback.classic.Logger
        val appender = ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>()
        appender.start()
        logger.addAppender(appender)
        try {
            observabilityTestApp {
                get("/ping") { call.respondText("pong") }
            }

            client.get("/ping")

            val accessLines = appender.list
                .map { it.formattedMessage }
                .filter { it.contains("/ping") }
            assertTrue(accessLines.isNotEmpty(), "expected an access log line for /ping")
            accessLines.forEach { line ->
                assertFalse(line.contains('\u001B'), "ANSI escape leaked into log line: $line")
            }
        } finally {
            logger.detachAppender(appender)
            appender.stop()
        }
    }
}
