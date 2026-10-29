package com.bm.backend.routes

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.bm.backend.models.ComparatorReportPdfRequest
import com.bm.backend.models.ReportDataset
import com.bm.backend.models.ReportDatasetRequest
import com.bm.backend.models.UserTier
import com.bm.backend.services.AccessControlService
import com.bm.backend.services.ComparatorReportPdfService
import com.bm.backend.services.JobService
import com.bm.backend.services.PremiumReportRenderer
import com.bm.backend.services.PremiumReportService
import com.bm.backend.services.ReportDatasetService
import com.bm.backend.services.UnavailablePremiumReportRenderer
import com.bm.backend.services.UserConsumptionService
import com.bm.backend.services.premiumreport.PdfPremiumReportRenderer
import com.bm.backend.services.premiumreport.PremiumReportSection
import com.bm.backend.testing.PdfInspection
import com.bm.backend.testing.PremiumReportFixtures
import com.bm.backend.testing.InMemoryGrantedUsersRepository
import com.bm.backend.testing.ReportComparisonFixtures
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readBytes
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
import io.mockk.mockk
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PremiumReportRoutesTest {
    private val cups = "ES0000000000000000AA0F"
    private val core = "ES0000000000000000AA"
    private val pdfBytes = byteArrayOf(37, 80, 68, 70, 1, 2, 3)
    private val oneHourMs = 60 * 60 * 1000L

    private val users = mapOf(
        "token-a" to AuthenticatedFirebaseUser("uid-a", "a@example.com", "A", Instant.ofEpochSecond(1), Instant.ofEpochSecond(2)),
        "token-b" to AuthenticatedFirebaseUser("uid-b", "b@example.com", "B", Instant.ofEpochSecond(1), Instant.ofEpochSecond(2)),
        "token-admin" to AuthenticatedFirebaseUser("uid-x", "admin@example.com", "X", Instant.ofEpochSecond(1), Instant.ofEpochSecond(2))
    )

    private class CountingRenderer(private val bytes: ByteArray, private val failure: Exception? = null) : PremiumReportRenderer {
        val calls = AtomicInteger()
        var last: ReportDataset? = null
        override fun render(dataset: ReportDataset): ByteArray {
            calls.incrementAndGet()
            last = dataset
            failure?.let { throw it }
            return bytes
        }
    }

    private class Ctx(
        val jobs: JobService,
        val grants: InMemoryGrantedUsersRepository,
        val renderer: PremiumReportRenderer,
        var nowOffsetMs: Long = 0
    )

    private fun ApplicationTestBuilder.setup(ctx: Ctx) {
        environment { config = MapApplicationConfig() }
        val access = AccessControlService(ctx.grants)
        val verify: suspend (String) -> AuthenticatedFirebaseUser = { token ->
            users[token] ?: throw mockk<com.google.firebase.auth.FirebaseAuthException>(relaxed = true)
        }
        application {
            install(ContentNegotiation) { json() }
            routing {
                premiumReportRoutes(
                    PremiumReportService(
                        ReportDatasetService(ctx.jobs) { System.currentTimeMillis() + ctx.nowOffsetMs },
                        ctx.renderer
                    ),
                    access,
                    verify
                )
                userConsumptionRoutes(
                    userConsumptionService = mockk<UserConsumptionService>(relaxed = true),
                    jobService = ctx.jobs,
                    comparatorReportPdfService = ComparatorReportPdfService(),
                    accessControlService = access,
                    verifyToken = verify
                )
            }
        }
    }

    private fun ctx(tierA: UserTier = UserTier.PREMIUM, renderer: PremiumReportRenderer = CountingRenderer(pdfBytes)) = Ctx(
        jobs = JobService(),
        grants = InMemoryGrantedUsersRepository().apply {
            insert("a@example.com", tierA)
            insert("b@example.com", UserTier.PREMIUM)
        },
        renderer = renderer
    )

    private fun completedJob(jobs: JobService, owner: String = "uid-a"): Pair<String, ComparatorReportPdfRequest> {
        val study = ReportComparisonFixtures.study(cups)
        val jobId = jobs.createJob(owner)
        jobs.completeJob(jobId, study.report, study.history, study.snapshot)
        return jobId to ReportComparisonFixtures.comparison(study.report, core)
    }

    private fun body(jobId: String, comparison: ComparatorReportPdfRequest) =
        Json.encodeToString(ReportDatasetRequest.serializer(), ReportDatasetRequest(jobId, comparison))

    private suspend fun ApplicationTestBuilder.premium(token: String?, body: String, auth: String? = null): HttpResponse =
        client.post("/reports/premium-pdf") {
            when {
                auth != null -> header(HttpHeaders.Authorization, auth)
                token != null -> header(HttpHeaders.Authorization, "Bearer $token")
            }
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject
    private suspend fun HttpResponse.code(): String = json().getValue("code").jsonPrimitive.content

    /** Fixture values that must never appear in any non-200 body or in logs. */
    private fun sensitive(jobId: String, comparison: ComparatorReportPdfRequest): List<String> = listOf(
        cups, core, comparison.supplyHolder, comparison.supplyAddress, jobId, "a@example.com",
        comparison.customerConditions.totalAnnualPrice
    ) + comparison.proposals.map { it.title } + comparison.proposals.map { it.totalAnnualPrice }

    private suspend fun HttpResponse.assertNoLeak(values: List<String>) {
        val text = bodyAsText()
        values.forEach { assertFalse(text.contains(it), "leaked '$it' in $text") }
    }

    private fun countOf(renderer: PremiumReportRenderer) = (renderer as CountingRenderer).calls.get()

    @Test
    fun `A premium owner gets the pdf and the renderer runs once with the request comparison`() = testApplication {
        val c = ctx(); setup(c)
        val (jobId, comparison) = completedJob(c.jobs)
        val response = premium("token-a", body(jobId, comparison))
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(ContentType.Application.Pdf, response.contentType()?.withoutParameters())
        assertContentEquals(pdfBytes, response.readBytes())
        val disposition = response.headers[HttpHeaders.ContentDisposition].orEmpty()
        assertTrue(Regex("attachment; filename=\"?informe_completo_\\d+\\.pdf\"?").matches(disposition), disposition)
        assertEquals(1, countOf(c.renderer))
        assertEquals(comparison, (c.renderer as CountingRenderer).last!!.comparison)
    }

    @Test
    fun `B unauthenticated requests are 401 and never render`() = testApplication {
        val c = ctx(); setup(c)
        val (jobId, comparison) = completedJob(c.jobs)
        val b = body(jobId, comparison)
        listOf(premium(null, b), premium(null, b, auth = "Basic abc"), premium(null, b, auth = "Bearer  "),
            premium("bad-token", b)).forEach {
            assertEquals(HttpStatusCode.Unauthorized, it.status)
            it.assertNoLeak(sensitive(jobId, comparison))
        }
        assertEquals(0, countOf(c.renderer))
    }

    @Test
    fun `C non-granted and revoked accounts are 403 with the existing message`() = testApplication {
        val c = ctx(); setup(c)
        val (jobId, comparison) = completedJob(c.jobs)
        val b = body(jobId, comparison)
        val admin = premium("token-admin", b)
        assertEquals(HttpStatusCode.Forbidden, admin.status)
        assertTrue(admin.bodyAsText().contains("no está autorizada para acceder a la aplicación"))
        assertEquals(HttpStatusCode.OK, premium("token-a", b).status)
        c.grants.deleteByEmail("a@example.com")
        val revoked = premium("token-a", b)
        assertEquals(HttpStatusCode.Forbidden, revoked.status)
        assertEquals(admin.bodyAsText(), revoked.bodyAsText())
        assertEquals(1, countOf(c.renderer))
    }

    @Test
    fun `D basic is premium_required before body parsing, tier changes apply on the next call`() = testApplication {
        val c = ctx(tierA = UserTier.BASIC); setup(c)
        val (jobId, comparison) = completedJob(c.jobs)
        val b = body(jobId, comparison)
        val valid = premium("token-a", b)
        assertEquals(HttpStatusCode.Forbidden, valid.status)
        assertEquals("premium_required", valid.code())
        valid.assertNoLeak(sensitive(jobId, comparison))
        val malformed = premium("token-a", "{not json")
        assertEquals(HttpStatusCode.Forbidden, malformed.status)
        assertEquals("premium_required", malformed.code())
        assertEquals(0, countOf(c.renderer))

        c.grants.updateTier("a@example.com", UserTier.PREMIUM)
        assertEquals(HttpStatusCode.OK, premium("token-a", b).status)
        c.grants.updateTier("a@example.com", UserTier.BASIC)
        val downgraded = premium("token-a", b)
        assertEquals(HttpStatusCode.Forbidden, downgraded.status)
        assertEquals("premium_required", downgraded.code())
        assertEquals(1, countOf(c.renderer))
    }

    @Test
    fun `E foreign, missing and expired studies give identical 404 responses`() = testApplication {
        val c = ctx(); setup(c)
        val (foreignId, comparison) = completedJob(c.jobs, owner = "uid-b")
        val (ownId, _) = completedJob(c.jobs)
        val foreign = premium("token-a", body(foreignId, comparison))
        val missing = premium("token-a", body("00000000-0000-0000-0000-000000000000", comparison))
        c.nowOffsetMs = oneHourMs + 1_000
        val expired = premium("token-a", body(ownId, comparison))
        c.nowOffsetMs = 0
        val bodies = listOf(foreign, missing, expired).map { r ->
            assertEquals(HttpStatusCode.NotFound, r.status)
            assertEquals("study_not_found", r.code())
            r.assertNoLeak(sensitive(foreignId, comparison) + ownId)
            JsonObject(r.json() - "requestId").toString()
        }
        assertEquals(1, bodies.toSet().size, bodies.toString())
        assertTrue(bodies.first().contains("ha caducado"))
        assertEquals(0, countOf(c.renderer))
        // Still readable before expiry.
        assertEquals(HttpStatusCode.OK, premium("token-a", body(ownId, comparison)).status)
    }

    @Test
    fun `E unfinished studies are 409 and validation failures 422 with their code`() = testApplication {
        val c = ctx(); setup(c)
        val (jobId, comparison) = completedJob(c.jobs)
        val pending = c.jobs.createJob("uid-a")
        val failed = c.jobs.createJob("uid-a").also { c.jobs.failJob(it, "boom") }
        listOf(pending, failed).forEach { id ->
            val r = premium("token-a", body(id, comparison))
            assertEquals(HttpStatusCode.Conflict, r.status)
            assertEquals("study_not_ready", r.code())
            r.assertNoLeak(sensitive(id, comparison))
        }
        val mismatch = premium("token-a", body(jobId, comparison.copy(cups = "ES0000000000000000BB")))
        assertEquals(HttpStatusCode.UnprocessableEntity, mismatch.status)
        assertEquals("cups_mismatch", mismatch.code())
        mismatch.assertNoLeak(sensitive(jobId, comparison) + "ES0000000000000000BB")
        val holder = premium("token-a", body(jobId, comparison.copy(supplyHolder = "--")))
        assertEquals(HttpStatusCode.UnprocessableEntity, holder.status)
        assertEquals("holder_missing", holder.code())
        assertEquals(0, countOf(c.renderer))
    }

    @Test
    fun `F malformed body or missing jobId is 400 invalid_request`() = testApplication {
        val c = ctx(); setup(c)
        val (jobId, comparison) = completedJob(c.jobs)
        val noJob = Json.parseToJsonElement(body(jobId, comparison)).jsonObject.let { JsonObject(it - "jobId") }.toString()
        listOf(premium("token-a", "{not json"), premium("token-a", noJob)).forEach {
            assertEquals(HttpStatusCode.BadRequest, it.status)
            assertEquals("invalid_request", it.code())
            it.assertNoLeak(sensitive(jobId, comparison))
        }
        assertEquals(0, countOf(c.renderer))
    }

    @Test
    fun `G default renderer answers 501 report_unavailable`() = testApplication {
        val c = ctx(renderer = UnavailablePremiumReportRenderer()); setup(c)
        val (jobId, comparison) = completedJob(c.jobs)
        val r = premium("token-a", body(jobId, comparison))
        assertEquals(HttpStatusCode.NotImplemented, r.status)
        assertEquals("report_unavailable", r.code())
        r.assertNoLeak(sensitive(jobId, comparison))
    }

    @Test
    fun `G renderer failure is 500 without exception text in body or logs`() = testApplication {
        val renderer = CountingRenderer(pdfBytes, IllegalStateException("$cups Cliente Ficticio SL"))
        val c = ctx(renderer = renderer); setup(c)
        val (jobId, comparison) = completedJob(c.jobs)
        val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        root.addAppender(appender)
        try {
            val r = premium("token-a", body(jobId, comparison))
            assertEquals(HttpStatusCode.InternalServerError, r.status)
            assertEquals("report_generation_failed", r.code())
            r.assertNoLeak(sensitive(jobId, comparison) + "Cliente" + "IllegalStateException")
            assertEquals(1, renderer.calls.get())
            val logs = appender.list.map { e ->
                e.formattedMessage + (e.throwableProxy?.let { " ${it.className} ${it.message}" } ?: "")
            }
            assertTrue(logs.any { it.contains("report_generation_failed") || it.contains("errorType=IllegalStateException") })
            logs.forEach { line ->
                sensitive(jobId, comparison).forEach { assertFalse(line.contains(it), "log leaked '$it': $line") }
            }
        } finally {
            root.detachAppender(appender)
        }
    }

    @Test
    fun `I basic comparator-pdf still works for both tiers`() = testApplication {
        val c = ctx(tierA = UserTier.BASIC); setup(c)
        val (_, comparison) = completedJob(c.jobs)
        listOf("token-a", "token-b").forEach { token ->
            val r = client.post("/reports/comparator-pdf") {
                header(HttpHeaders.Authorization, "Bearer $token")
                contentType(ContentType.Application.Json)
                setBody(Json.encodeToString(ComparatorReportPdfRequest.serializer(), comparison))
            }
            assertEquals(HttpStatusCode.OK, r.status)
            assertEquals(ContentType.Application.Pdf, r.contentType()?.withoutParameters())
            assertTrue(r.headers[HttpHeaders.ContentDisposition].orEmpty().contains("comparativo_"))
        }
        assertEquals(0, countOf(c.renderer))
    }

    // ---- report-document: real renderer ----

    @Test
    fun `A real renderer returns the assembled pdf`() = testApplication {
        val c = ctx(renderer = PdfPremiumReportRenderer(PremiumReportFixtures.CLOCK)); setup(c)
        val (jobId, comparison) = completedJob(c.jobs)
        val r = premium("token-a", body(jobId, comparison))
        assertEquals(HttpStatusCode.OK, r.status)
        assertEquals(ContentType.Application.Pdf, r.contentType()?.withoutParameters())
        val disposition = r.headers[HttpHeaders.ContentDisposition].orEmpty()
        assertTrue(Regex("attachment; filename=\"?informe_completo_\\d+\\.pdf\"?").matches(disposition), disposition)
        val pdf = PdfInspection(r.readBytes())
        assertTrue(pdf.isPdf)
        assertTrue(pdf.pageCount >= 4)
        assertTrue(pdf.pageTexts.last().contains("Página ${pdf.pageCount} de ${pdf.pageCount}"))
    }

    @Test
    fun `H real renderer - basic is 403 premium_required`() = testApplication {
        val c = ctx(tierA = UserTier.BASIC, renderer = PdfPremiumReportRenderer(PremiumReportFixtures.CLOCK)); setup(c)
        val (jobId, comparison) = completedJob(c.jobs)
        val r = premium("token-a", body(jobId, comparison))
        assertEquals(HttpStatusCode.Forbidden, r.status)
        assertEquals("premium_required", r.code())
    }

    @Test
    fun `G real renderer - section failure is 500 without bytes or leaks, next request succeeds`() = testApplication {
        val fail = java.util.concurrent.atomic.AtomicBoolean(true)
        val renderer = PdfPremiumReportRenderer(PremiumReportFixtures.CLOCK) { d ->
            val sections = PdfPremiumReportRenderer(PremiumReportFixtures.CLOCK).defaultSections(d)
            if (fail.get()) sections.take(2) + PremiumReportSection { _, _, ds -> error("${ds.cups} ${ds.comparison.supplyHolder}") } else sections
        }
        val c = ctx(renderer = renderer); setup(c)
        val (jobId, comparison) = completedJob(c.jobs)
        val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        root.addAppender(appender)
        try {
            val r = premium("token-a", body(jobId, comparison))
            assertEquals(HttpStatusCode.InternalServerError, r.status)
            assertEquals("report_generation_failed", r.code())
            assertEquals(ContentType.Application.Json, r.contentType()?.withoutParameters())
            assertEquals(null, r.headers[HttpHeaders.ContentDisposition])
            assertFalse(r.bodyAsText().contains("%PDF"))
            r.assertNoLeak(sensitive(jobId, comparison))
            appender.list.map { e -> e.formattedMessage + (e.throwableProxy?.let { " ${it.className} ${it.message}" } ?: "") }
                .forEach { line -> sensitive(jobId, comparison).forEach { assertFalse(line.contains(it), "log leaked '$it': $line") } }
        } finally {
            root.detachAppender(appender)
        }
        fail.set(false)
        val ok = premium("token-a", body(jobId, comparison))
        assertEquals(HttpStatusCode.OK, ok.status)
        assertTrue(PdfInspection(ok.readBytes()).isPdf)
    }
}
