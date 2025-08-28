package com.bm.backend

import com.bm.backend.database.DatabaseFactory
import com.bm.backend.models.PriceTableResponse
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class ApplicationTest {

    @BeforeEach
    fun setup() {
        DatabaseFactory.initTestDatabase()
    }

    @AfterEach
    fun tearDown() {
        java.io.File("test_price_tables.db").delete()
    }

    private fun Application.testApplicationModule() {
        configureRouting()
    }

    @Test
    fun testApplicationModule() = testApplication {
        application {
            testApplicationModule()
        }
    }

    @Test
    fun `test root endpoint returns service status`() = testApplication {
        application {
            testApplicationModule()
        }
        client.get("/").apply {
            assertEquals(HttpStatusCode.OK, status)
        }
    }

    @Test
    fun `test health check endpoint`() = testApplication {
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
        application {
            testApplicationModule()
        }

        val createdEntries = 16

        client.get("/api/v1/price-table-results").apply {
            assertEquals(HttpStatusCode.OK, status)
            val response = Json.decodeFromString<PriceTableResponse>(bodyAsText())
            assertEquals(true, response.success)
            assertEquals(createdEntries, response.results.size)
        }
    }

    @Test
    fun `test batch process endpoint with empty data`() = testApplication {
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
