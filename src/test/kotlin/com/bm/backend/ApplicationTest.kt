package com.bm.backend

import com.bm.backend.database.DatabaseFactory
import com.bm.backend.models.ExtractedTables
import com.bm.backend.models.StorePriceTablesRequest
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.testing.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ApplicationTest {

    @BeforeEach
    fun setup() {
        System.setProperty("test.mode", "true")
        DatabaseFactory.initTestDatabase()
    }

    @AfterEach
    fun tearDown() {
        System.clearProperty("test.mode")
        java.io.File("test_price_tables.db").let { 
            if (it.exists()) it.delete() 
        }
    }

    private fun Application.testApplicationModule() {
        // Correctly invoke the extension function defined in Application.kt
        this.testModule()
    }

    @Test
    fun `test root endpoint returns service status`() = testApplication {
        application {
            testApplicationModule()
        }
        
        client.get("/").apply {
            assertEquals(HttpStatusCode.OK, status)
            val response = bodyAsText()
            assertTrue(response.contains("Price Table Backend Service is running"))
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
            assertNotNull(response["timestamp"])
        }
    }

    @Test
    fun `test store price tables endpoint with valid data`() = testApplication {
        application {
            testApplicationModule()
        }

        val request = StorePriceTablesRequest(
            filename = "test.pdf",
            extracted_tables = ExtractedTables(
                termino_potencia = JsonObject(mapOf("key" to JsonPrimitive("value"))),
                termino_energia_clasica_base = JsonObject(mapOf("data" to JsonPrimitive("sample")))
            ),
            source = "test-source"
        )

        client.post("/api/v1/store-price-tables") {
            contentType(ContentType.Application.Json)
            setBody(Json.encodeToString(request))
        }.apply {
            assertEquals(HttpStatusCode.Created, status)
            val response = Json.decodeFromString<JsonObject>(bodyAsText())
            assertEquals(true, response["success"]?.jsonPrimitive?.booleanOrNull)
            assertTrue(response["record_id"]?.jsonPrimitive?.intOrNull ?: 0 > 0)
        }
    }

    @Test
    fun `test store price tables endpoint with invalid data`() = testApplication {
        application {
            testApplicationModule()
        }

        val invalidRequest = JsonObject(mapOf(
            "filename" to JsonPrimitive(""),
            "source" to JsonPrimitive(""),
            "extracted_tables" to JsonObject(emptyMap())
        ))

        client.post("/api/v1/store-price-tables") {
            contentType(ContentType.Application.Json)
            setBody(Json.encodeToString(invalidRequest))
        }.apply {
            assertEquals(HttpStatusCode.BadRequest, status)
        }
    }

    @Test
    fun `test get all price tables endpoint`() = testApplication {
        application {
            testApplicationModule()
        }

        // First store some test data
        val request = StorePriceTablesRequest(
            filename = "test.pdf",
            extracted_tables = ExtractedTables(
                termino_potencia = JsonObject(mapOf("key" to JsonPrimitive("value")))
            ),
            source = "test-source"
        )

        client.post("/api/v1/store-price-tables") {
            contentType(ContentType.Application.Json)
            setBody(Json.encodeToString(request))
        }

        client.get("/api/v1/price-tables").apply {
            assertEquals(HttpStatusCode.OK, status)
            val response = Json.decodeFromString<JsonObject>(bodyAsText())
            assertEquals(true, response["success"]?.jsonPrimitive?.booleanOrNull)
            assertTrue((response["data"]?.jsonArray?.size ?: 0) >= 1)
        }
    }

    @Test
    fun `test get price table by id endpoint`() = testApplication {
        application {
            testApplicationModule()
        }

        // First store test data
        val request = StorePriceTablesRequest(
            filename = "test.pdf",
            extracted_tables = ExtractedTables(
                termino_potencia = JsonObject(mapOf("key" to JsonPrimitive("value")))
            ),
            source = "test-source"
        )

        val storeResponse = client.post("/api/v1/store-price-tables") {
            contentType(ContentType.Application.Json)
            setBody(Json.encodeToString(request))
        }

        val storeJson = Json.decodeFromString<JsonObject>(storeResponse.bodyAsText())
        val recordId = storeJson["record_id"]?.jsonPrimitive?.intOrNull

        client.get("/api/v1/price-tables/$recordId").apply {
            assertEquals(HttpStatusCode.OK, status)
            val response = Json.decodeFromString<JsonObject>(bodyAsText())
            assertEquals(true, response["success"]?.jsonPrimitive?.booleanOrNull)
            assertEquals("test.pdf", response["data"]?.jsonObject?.get("filename")?.jsonPrimitive?.content)
        }
    }

    @Test
    fun `test get price table by id with invalid id`() = testApplication {
        application {
            testApplicationModule()
        }

        client.get("/api/v1/price-tables/abc").apply {
            assertEquals(HttpStatusCode.BadRequest, status)
        }
    }

    @Test
    fun `test get price table by id with non-existent id`() = testApplication {
        application {
            testApplicationModule()
        }

        client.get("/api/v1/price-tables/999").apply {
            assertEquals(HttpStatusCode.NotFound, status)
        }
    }
}
