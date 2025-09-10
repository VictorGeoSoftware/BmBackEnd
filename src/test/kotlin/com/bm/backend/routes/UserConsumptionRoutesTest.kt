package com.bm.backend.routes

import com.bm.backend.models.ErrorResponse
import com.bm.backend.models.UserConsumption
import com.bm.backend.services.UserConsumptionService
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UserConsumptionRoutesTest {

    private val mockUserConsumptionService = mockk<UserConsumptionService>()

    private fun Application.configureTestModule() {
        routing {
            route("/api/v1") {
                userConsumptionRoutes(mockUserConsumptionService)
            }
        }
    }

    @Test
    fun `test successful consumption report processing`() = testApplication {
        application {
            configureTestModule()
        }

        val validJson = """
        {
            "data": [
                {
                    "Fecha Lectura Inicial": "2022-07-31T00:00:00",
                    "Fecha Lectura Final": "2022-08-31T00:00:00",
                    "Activa": [0, 0, 5230000, 3523000, 0, 1704000],
                    "Reactiva": [0, 0, 958100, 540410, 0, 0],
                    "Maximetro": [0, 0, 77000, 73000, 0, 67000]
                },
                {
                    "Fecha Lectura Inicial": "2022-08-31T00:00:00",
                    "Fecha Lectura Final": "2022-09-30T00:00:00",
                    "Activa": [0, 0, 4850000, 3200000, 0, 1500000],
                    "Reactiva": [0, 0, 800000, 450000, 0, 0],
                    "Maximetro": [0, 0, 75000, 70000, 0, 65000]
                }
            ]
        }
        """.trimIndent()

        client.post("/api/v1/consumption-report") {
            contentType(ContentType.Application.Json)
            setBody(validJson)
        }.apply {
            assertEquals(HttpStatusCode.OK, status)
            verify(exactly = 1) { mockUserConsumptionService.processConsumptionReport(any()) }
        }
    }

    @Test
    fun `test JSON deserialization with proper body structure`() = testApplication {
        application {
            configureTestModule()
        }

        val validJson = """
        {
            "data": [
                {
                    "Fecha Lectura Inicial": "2022-07-31T00:00:00",
                    "Fecha Lectura Final": "2022-08-31T00:00:00",
                    "Activa": [1.0, 2.0, 3.0],
                    "Reactiva": [4.0, 5.0, 6.0],
                    "Maximetro": [7.0, 8.0, 9.0]
                }
            ]
        }
        """.trimIndent()

        // Mock the service to capture the parsed data
        every { mockUserConsumptionService.processConsumptionReport(any()) } just runs

        client.post("/api/v1/consumption-report") {
            contentType(ContentType.Application.Json)
            setBody(validJson)
        }.apply {
            assertEquals(HttpStatusCode.OK, status)
            verify(exactly = 1) { mockUserConsumptionService.processConsumptionReport(any()) }
        }
    }

    @Test
    fun `test error handling for invalid JSON structure`() = testApplication {
        application {
            configureTestModule()
        }

        val invalidJson = """
        {
            "wrongField": [
                {
                    "Fecha Lectura Inicial": "2022-07-31T00:00:00"
                }
            ]
        }
        """.trimIndent()

        client.post("/api/v1/consumption-report") {
            contentType(ContentType.Application.Json)
            setBody(invalidJson)
        }.apply {
            assertEquals(HttpStatusCode.InternalServerError, status)
            val response = Json.decodeFromString<ErrorResponse>(bodyAsText())
            assertTrue(response.message.contains("Internal server error"))
            verify(exactly = 0) { mockUserConsumptionService.processConsumptionReport(any()) }
        }
    }

    @Test
    fun `test error handling for malformed JSON`() = testApplication {
        application {
            configureTestModule()
        }

        val malformedJson = """
        {
            "data": [
                {
                    "Fecha Lectura Inicial": "2022-07-31T00:00:00",
                    "Fecha Lectura Final": "2022-08-31T00:00:00",
                    "Activa": "not an array"
                }
            ]
        }
        """.trimIndent()

        client.post("/api/v1/consumption-report") {
            contentType(ContentType.Application.Json)
            setBody(malformedJson)
        }.apply {
            assertEquals(HttpStatusCode.InternalServerError, status)
            val response = Json.decodeFromString<ErrorResponse>(bodyAsText())
            assertTrue(response.message.contains("Internal server error"))
            verify(exactly = 0) { mockUserConsumptionService.processConsumptionReport(any()) }
        }
    }

    @Test
    fun `test error handling for empty body array`() = testApplication {
        application {
            configureTestModule()
        }

        val emptyBodyJson = """
        {
            "data": []
        }
        """.trimIndent()

        client.post("/api/v1/consumption-report") {
            contentType(ContentType.Application.Json)
            setBody(emptyBodyJson)
        }.apply {
            assertEquals(HttpStatusCode.OK, status)
            verify(exactly = 1) { mockUserConsumptionService.processConsumptionReport(any()) }
        }
    }

    @Test
    fun `test error handling when service throws exception`() = testApplication {
        application {
            configureTestModule()
        }

        val validJson = """
        {
            "data": [
                {
                    "Fecha Lectura Inicial": "2022-07-31T00:00:00",
                    "Fecha Lectura Final": "2022-08-31T00:00:00",
                    "Activa": [1.0, 2.0, 3.0],
                    "Reactiva": [4.0, 5.0, 6.0],
                    "Maximetro": [7.0, 8.0, 9.0]
                }
            ]
        }
        """.trimIndent()

        // Mock service to throw an exception
        every { mockUserConsumptionService.processConsumptionReport(any()) } throws RuntimeException("Database error")

        client.post("/api/v1/consumption-report") {
            contentType(ContentType.Application.Json)
            setBody(validJson)
        }.apply {
            assertEquals(HttpStatusCode.InternalServerError, status)
            val response = Json.decodeFromString<ErrorResponse>(bodyAsText())
            assertTrue(response.message.contains("Database error"))
        }
    }

    @Test
    fun `test error handling for completely invalid JSON`() = testApplication {
        application {
            configureTestModule()
        }

        val invalidJson = "{ invalid json structure"

        client.post("/api/v1/consumption-report") {
            contentType(ContentType.Application.Json)
            setBody(invalidJson)
        }.apply {
            assertEquals(HttpStatusCode.InternalServerError, status)
            val response = Json.decodeFromString<ErrorResponse>(bodyAsText())
            assertTrue(response.message.contains("Internal server error"))
            verify(exactly = 0) { mockUserConsumptionService.processConsumptionReport(any()) }
        }
    }

    @Test
    fun `test multiple consumption periods in body`() = testApplication {
        application {
            configureTestModule()
        }

        val multiplePeriodsJson = """
        {
            "data": [
                {
                    "Fecha Lectura Inicial": "2022-07-31T00:00:00",
                    "Fecha Lectura Final": "2022-08-31T00:00:00",
                    "Activa": [1.0, 2.0, 3.0],
                    "Reactiva": [4.0, 5.0, 6.0],
                    "Maximetro": [7.0, 8.0, 9.0]
                },
                {
                    "Fecha Lectura Inicial": "2022-08-31T00:00:00",
                    "Fecha Lectura Final": "2022-09-30T00:00:00",
                    "Activa": [10.0, 20.0, 30.0],
                    "Reactiva": [40.0, 50.0, 60.0],
                    "Maximetro": [70.0, 80.0, 90.0]
                }
            ]
        }
        """.trimIndent()

        // Mock the service to verify multiple periods are processed
        every { mockUserConsumptionService.processConsumptionReport(any()) } answers {
            val consumptionReport = firstArg<UserConsumption>()
            assertEquals(2, consumptionReport.data.size)
        }

        client.post("/api/v1/consumption-report") {
            contentType(ContentType.Application.Json)
            setBody(multiplePeriodsJson)
        }.apply {
            assertEquals(HttpStatusCode.OK, status)
            verify(exactly = 1) { mockUserConsumptionService.processConsumptionReport(any()) }
        }
    }
}
