package com.bm.backend

import com.bm.backend.models.PriceTableResponse
import com.bm.backend.testing.DockerAvailable
import com.bm.backend.testing.PostgresTestSetup
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ApplicationTest {

    companion object {
        @BeforeAll
        @JvmStatic
        fun setup() {
            Assumptions.assumeTrue(DockerAvailable.check(), "Docker not available")
            PostgresTestSetup.ensureStarted()
        }
    }

    private fun Application.testApplicationModule() {
        // Skip DatabaseFactory.init() — Testcontainer DB is already connected
        val registry = configurePlugins()
        configureRouting(registry)
    }

    @Test
    fun testApplicationModule() = testApplication {
        environment {
            config = io.ktor.server.config.MapApplicationConfig()
        }
        application {
            testApplicationModule()
        }
    }

    @Test
    fun `test root endpoint returns service status`() = testApplication {
        environment {
            config = io.ktor.server.config.MapApplicationConfig()
        }
        application {
            testApplicationModule()
        }
        client.get("/").apply {
            assertEquals(HttpStatusCode.OK, status)
        }
    }

    @Test
    fun `test health check endpoint`() = testApplication {
        environment {
            config = io.ktor.server.config.MapApplicationConfig()
        }
        application {
            testApplicationModule()
        }
        client.get("/health").apply {
            assertEquals(HttpStatusCode.OK, status)
            val response = Json.decodeFromString<JsonObject>(bodyAsText())
            assertEquals("healthy", response["status"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun `liveness probe reports alive without checking dependencies`() = testApplication {
        environment {
            config = io.ktor.server.config.MapApplicationConfig()
        }
        application {
            testApplicationModule()
        }
        client.get("/health/live").apply {
            assertEquals(HttpStatusCode.OK, status)
            val response = Json.decodeFromString<JsonObject>(bodyAsText())
            assertEquals("alive", response["status"]?.jsonPrimitive?.content)
            // Liveness must not leak a dependency status, or operators will
            // start treating it as readiness again.
            assertTrue(response["database"] == null)
        }
    }

    @Test
    fun `readiness probe reports database connectivity`() = testApplication {
        environment {
            config = io.ktor.server.config.MapApplicationConfig()
        }
        application {
            testApplicationModule()
        }
        client.get("/health/ready").apply {
            assertEquals(HttpStatusCode.OK, status)
            val response = Json.decodeFromString<JsonObject>(bodyAsText())
            assertEquals("healthy", response["status"]?.jsonPrimitive?.content)
            assertEquals("connected", response["database"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun `health stays a byte-compatible alias of health ready`() = testApplication {
        environment {
            config = io.ktor.server.config.MapApplicationConfig()
        }
        application {
            testApplicationModule()
        }

        // Deploy workflows and Docker healthchecks still call /health. If the
        // alias ever diverges from /health/ready, CI breaks on the next deploy.
        val legacy = Json.decodeFromString<JsonObject>(client.get("/health").bodyAsText())
        val ready = Json.decodeFromString<JsonObject>(client.get("/health/ready").bodyAsText())

        assertEquals(ready.keys, legacy.keys)
        assertEquals(
            ready["status"]?.jsonPrimitive?.content,
            legacy["status"]?.jsonPrimitive?.content
        )
        assertEquals(
            ready["database"]?.jsonPrimitive?.content,
            legacy["database"]?.jsonPrimitive?.content
        )
    }

    @Test
    fun `metrics endpoint rejects unauthenticated callers`() = testApplication {
        environment {
            config = io.ktor.server.config.MapApplicationConfig()
        }
        application {
            testApplicationModule()
        }
        // METRICS_TOKEN is unset under test, so the endpoint must fail closed.
        client.get("/metrics").apply {
            assertEquals(HttpStatusCode.Unauthorized, status)
        }
        client.get("/metrics") {
            header("Authorization", "Bearer guessed-token")
        }.apply {
            assertEquals(HttpStatusCode.Unauthorized, status)
        }
    }

    @Test
    fun `probes are not consumed by the api rate limiter`() = testApplication {
        environment {
            config = io.ktor.server.config.MapApplicationConfig()
        }
        application {
            testApplicationModule()
        }

        // The limiter allows 100 requests per minute and is shared across all
        // callers. Before the split it was global, so infrastructure polling
        // competed with real traffic for that budget and a 429 on a Docker
        // healthcheck would have restarted a healthy container.
        repeat(150) {
            assertEquals(HttpStatusCode.OK, client.get("/health/live").status)
        }
        assertEquals(HttpStatusCode.OK, client.get("/health").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/metrics").status)
    }

    @Test
    fun `api routes are still rate limited after the limiter was rescoped`() = testApplication {
        environment {
            config = io.ktor.server.config.MapApplicationConfig()
        }
        application {
            testApplicationModule()
        }

        // Guards against the obvious way to break this change: moving the
        // limiter off `global` and forgetting to apply it to /api/v1, which
        // would silently remove rate limiting from every real endpoint.
        var sawTooManyRequests = false
        repeat(150) {
            if (client.get("/api/v1/price-table-results").status == HttpStatusCode.TooManyRequests) {
                sawTooManyRequests = true
            }
        }
        assertTrue(sawTooManyRequests, "Expected /api/v1 traffic to hit the rate limit")
    }

    @Test
    fun `price table results rejects unauthenticated callers`() = testApplication {
        environment {
            config = io.ktor.server.config.MapApplicationConfig()
        }
        application {
            testApplicationModule()
        }

        // This endpoint used to be readable with no credentials at all. It now
        // requires an authenticated (not necessarily admin) caller, so an
        // anonymous request must be rejected before it reaches the service.
        client.get("/api/v1/price-table-results").apply {
            assertEquals(HttpStatusCode.Unauthorized, status)
        }
    }

    @Test
    fun `test batch process endpoint with empty data`() = testApplication {
        environment {
            config = io.ktor.server.config.MapApplicationConfig()
        }
        application {
            testApplicationModule()
        }
        
        client.post("/api/v1/batch-process-price-tables") {
            contentType(ContentType.Application.Json)
            setBody("[]")
        }.apply {
            assertEquals(HttpStatusCode.BadRequest, status)
            val responseText = bodyAsText()
            assert(responseText.contains("Request cannot be empty"))
        }
    }
}
