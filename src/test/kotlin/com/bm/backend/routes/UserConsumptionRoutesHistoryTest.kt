package com.bm.backend.routes

import com.bm.backend.models.FilteredPriceTableResponse
import com.bm.backend.models.JobStatus
import com.bm.backend.models.StudyHistoryStatus
import com.bm.backend.models.TaxSettingsResponse
import com.bm.backend.services.AccessControlService
import com.bm.backend.services.ComparatorReportPdfService
import com.bm.backend.services.ExternalApiService
import com.bm.backend.services.JobService
import com.bm.backend.services.PriceTableService
import com.bm.backend.services.UserConsumptionService
import com.bm.backend.testing.InMemoryGrantedUsersRepository
import com.bm.backend.testing.InMemoryUserConsumptionRepository
import com.bm.backend.testing.N8nFixtures
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.fail

class UserConsumptionRoutesHistoryTest {
    private val cupsA = "ES0031607515707001RC"
    private val cupsB = "ES0031607515707001RC0F"

    private val users = mapOf(
        "token-a" to AuthenticatedFirebaseUser("uid-a", "a@example.com", "A", Instant.ofEpochSecond(1), Instant.ofEpochSecond(2)),
        "token-b" to AuthenticatedFirebaseUser("uid-b", "b@example.com", "B", Instant.ofEpochSecond(1), Instant.ofEpochSecond(2))
    )

    private val reportKeys = setOf("success", "userData", "consumptionData", "proposals", "iva", "impuestoElectrico")
    private val statusKeys = setOf("jobId", "status", "message", "progress", "result", "error")

    private fun ApplicationTestBuilder.setup(jobService: JobService) {
        environment { config = MapApplicationConfig() }
        val externalApiService = mockk<ExternalApiService>()
        coEvery { externalApiService.processWithN8nWebhook(match { it.cups_code == cupsA }) } returns
            N8nFixtures.response("history_v1.json", cupsA)
        coEvery { externalApiService.processWithN8nWebhook(match { it.cups_code == cupsB }) } returns
            N8nFixtures.response("history_unavailable_missing.json", cupsB)
        val priceTableService = mockk<PriceTableService>()
        every { priceTableService.getFilteredPriceTableResults(any()) } returns
            FilteredPriceTableResponse(success = true, results = emptyList(), iva = 0.21, impuestoElectrico = 0.0511)
        every { priceTableService.getTaxSettings() } returns
            TaxSettingsResponse(success = true, iva = 0.10, impuestoElectrico = 0.02)
        val grants = InMemoryGrantedUsersRepository().apply {
            insert("a@example.com")
            insert("b@example.com")
        }
        application {
            install(ContentNegotiation) { json() }
            routing {
                userConsumptionRoutes(
                    userConsumptionService = UserConsumptionService(
                        InMemoryUserConsumptionRepository(), externalApiService, priceTableService
                    ),
                    jobService = jobService,
                    comparatorReportPdfService = ComparatorReportPdfService(),
                    accessControlService = AccessControlService(grants),
                    verifyToken = { token -> users.getValue(token) }
                )
            }
        }
    }

    private suspend fun HttpClient.startJob(token: String, cups: String): String {
        val response = post("/fetch-user-consumption-report-by-cups") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"cupsCode":"$cups"}""")
        }
        assertEquals(HttpStatusCode.Accepted, response.status)
        return Json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("jobId").jsonPrimitive.content
    }

    private suspend fun awaitCompleted(jobService: JobService, jobId: String) {
        repeat(200) {
            if (jobService.getJob(jobId)?.status == JobStatus.COMPLETED) return
            delay(25)
        }
        fail("Job did not complete: ${jobService.getJob(jobId)?.status} ${jobService.getJob(jobId)?.error}")
    }

    @Test
    fun `concurrent owners keep their own history and cannot read each other's jobs`() = testApplication {
        val jobService = JobService()
        setup(jobService)
        val (jobA, jobB) = coroutineScope {
            val a = async { client.startJob("token-a", cupsA) }
            val b = async { client.startJob("token-b", cupsB) }
            a.await() to b.await()
        }
        awaitCompleted(jobService, jobA)
        awaitCompleted(jobService, jobB)

        val historyA = assertNotNull(jobService.getHistory(jobA, "uid-a"))
        val historyB = assertNotNull(jobService.getHistory(jobB, "uid-b"))
        assertEquals(StudyHistoryStatus.AVAILABLE, historyA.status)
        assertEquals(cupsA, historyA.cups)
        assertEquals(16.103, historyA.intervals[0].maxDemand.p1)
        assertEquals(StudyHistoryStatus.UNAVAILABLE, historyB.status)
        assertEquals("missing", historyB.reason)
        assertEquals(cupsB, historyB.cups)
        assertEquals(null, jobService.getHistory(jobA, "uid-b"))

        val bearerB: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {
            header(HttpHeaders.Authorization, "Bearer token-b")
        }
        assertEquals(HttpStatusCode.NotFound, client.get("/consumption-report-status/$jobA", bearerB).status)
        assertEquals(HttpStatusCode.NotFound, client.get("/consumption-report-result/$jobA", bearerB).status)
        assertEquals(HttpStatusCode.NotFound, client.post("/consumption-report-refresh/$jobA", bearerB).status)
        assertSame(historyA, jobService.getHistory(jobA, "uid-a"))
    }

    @Test
    fun `client JSON never exposes history and refresh keeps it`() = testApplication {
        val jobService = JobService()
        setup(jobService)
        val jobId = client.startJob("token-a", cupsA)
        awaitCompleted(jobService, jobId)
        val history = jobService.getHistory(jobId, "uid-a")!!
        val bearerA: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {
            header(HttpHeaders.Authorization, "Bearer token-a")
        }

        val status = client.get("/consumption-report-status/$jobId", bearerA)
        assertEquals(HttpStatusCode.OK, status.status)
        val statusBody = status.bodyAsText()
        assertFalse(statusBody.contains("history"))
        val statusJson = Json.parseToJsonElement(statusBody).jsonObject
        assertEquals(statusKeys, statusJson.keys)
        assertEquals(reportKeys, (statusJson.getValue("result") as JsonObject).keys)

        val result = client.get("/consumption-report-result/$jobId", bearerA)
        assertEquals(HttpStatusCode.OK, result.status)
        assertFalse(result.bodyAsText().contains("history"))
        assertEquals(reportKeys, Json.parseToJsonElement(result.bodyAsText()).jsonObject.keys)

        val before = jobService.getJob(jobId)!!.result!!
        val refresh = client.post("/consumption-report-refresh/$jobId", bearerA)
        assertEquals(HttpStatusCode.OK, refresh.status)
        assertFalse(refresh.bodyAsText().contains("history"))
        val after = jobService.getJob(jobId)!!.result!!
        assertEquals(0.10, after.iva)
        assertNotEquals(before.iva, after.iva)
        assertEquals(before.consumptionData, after.consumptionData)
        assertSame(history, jobService.getHistory(jobId, "uid-a"))
    }
}
