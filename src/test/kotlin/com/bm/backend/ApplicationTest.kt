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
    fun `test get price table results endpoint`() = testApplication {
        environment {
            config = io.ktor.server.config.MapApplicationConfig()
        }
        application {
            testApplicationModule()
        }

        client.get("/api/v1/price-table-results").apply {
            assertEquals(HttpStatusCode.OK, status)
            val response = Json.decodeFromString<PriceTableResponse>(bodyAsText())
            assertEquals(true, response.success)
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
