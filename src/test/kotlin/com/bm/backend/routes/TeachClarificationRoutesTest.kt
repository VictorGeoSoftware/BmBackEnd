package com.bm.backend.routes

import com.bm.backend.configurePlugins
import com.bm.backend.models.TeachClarificationCandidate
import com.bm.backend.models.TeachClarificationReference.Kind
import com.bm.backend.models.TeachWorkbookAuthDecision.Allowed
import com.bm.backend.services.AdminAccessControlService
import com.bm.backend.services.TeachClarificationAdapter
import com.bm.backend.services.TeachMeAccessPolicy
import com.bm.backend.services.fixedTeachClarificationAdapter
import com.bm.backend.testing.*
import com.google.firebase.ErrorCode
import com.google.firebase.auth.AuthErrorCode
import com.google.firebase.auth.FirebaseAuthException
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.engine.applicationEngineEnvironment
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationCall
import io.ktor.server.request.ApplicationReceivePipeline
import io.ktor.server.request.header
import io.ktor.server.routing.*
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.time.Instant
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class TeachClarificationRoutesTest {
    private val endpoint = "/api/v1/teach-me/clarifications"
    private val admin = "fixture@example.invalid"
    private val workbook = clarificationWorkbook()
    private val pdf = syntheticReferencePdf()
    private val png = syntheticReferencePng()
    private val digest = sha256(workbook)
    private val answer = "La columna C"
    private val ask = clarificationFrame("""[1,"ask",${workbook.size}]""", workbook)
    private val coordinates = listOf(TeachClarificationCandidate(0, "B2"), TeachClarificationCandidate(0, "C2"))

    private fun manifest(xlsx: ByteArray, pdf: ByteArray, png: ByteArray, answer: String, expected: String) =
        """[1,"reply",${xlsx.size},${pdf.size},${png.size},"$expected",${JsonPrimitive(answer)}]"""

    private fun reply(xlsx: ByteArray = workbook, pdf: ByteArray = this.pdf, png: ByteArray = this.png,
                      answer: String = this.answer, expected: String = digest): ByteArray =
        clarificationFrame(manifest(xlsx, pdf, png, answer, expected), xlsx, pdf, png)

    private fun Application.mount(
        admins: InMemoryAdminUsersRepository,
        environment: String = "qa",
        enabled: Boolean = true,
        adapter: TeachClarificationAdapter = fixedTeachClarificationAdapter,
    ) {
        routing { route("/api/v1") {
            teachMeAccessRoutes(AdminAccessControlService(admins), TeachMeAccessPolicy(environment, admin), enabled, adapter) { token ->
                if (token != "fixture-token" && token != "other") throw FirebaseAuthException(
                    ErrorCode.UNAUTHENTICATED, "private fixture detail", null, null, AuthErrorCode.INVALID_ID_TOKEN,
                )
                AuthenticatedFirebaseUser("fixture", if (token == "other") "other@example.invalid" else admin, null, Instant.EPOCH, Instant.MAX)
            }
        } }
    }

    private fun server(module: Application.() -> Unit) = embeddedServer(Netty, applicationEngineEnvironment {
        config = MapApplicationConfig()
        connector { host = "127.0.0.1"; port = 0 }
        module(module)
    })

    private fun begin(port: Int, bytes: ByteArray, token: String? = "fixture-token", path: String = endpoint, extra: String = "") = Socket().apply {
        soTimeout = 5_000
        connect(InetSocketAddress("127.0.0.1", port), 2_000)
        getOutputStream().apply {
            val auth = token?.let { "Authorization: Bearer $it\r\n" }.orEmpty()
            write(("POST $path HTTP/1.1\r\nHost: 127.0.0.1\r\n${auth}Content-Type: $CLARIFICATION_MEDIA_TYPE\r\n" +
                "${extra}Connection: close\r\nTransfer-Encoding: chunked\r\n\r\n").toByteArray())
            if (bytes.isNotEmpty()) {
                write("${bytes.size.toString(16)}\r\n".toByteArray())
                write(bytes)
                write("\r\n".toByteArray())
            }
            write("0\r\n\r\n".toByteArray())
            flush()
        }
    }

    private fun expectStatus(status: Int, raw: String): String {
        assertTrue(raw.startsWith("HTTP/1.1 $status "), raw.substringBefore("\r\n"))
        assertContains(raw.substringBefore("\r\n\r\n").lowercase(), "cache-control: no-store")
        return raw.substringAfter("\r\n\r\n")
    }

    @Test fun `Netty QA guard checks authorization before body and rechecks on reply with opaque files`() = runBlocking {
        val reads = AtomicInteger()
        val completion = TeachCallCompletion()
        val turns = AtomicInteger()
        val admins = InMemoryAdminUsersRepository().apply { add(admin); add("other@example.invalid") }
        val server = server {
            configurePlugins()
            completion.install(this)
            receivePipeline.intercept(ApplicationReceivePipeline.Before) { reads.incrementAndGet() }
            mount(admins, adapter = TeachClarificationAdapter { context ->
                assertEquals(coordinates, context.candidates)
                val index = turns.getAndIncrement()
                when (index) {
                    0 -> { assertNull(context.answer); assertTrue(context.references.isEmpty()) }
                    else -> {
                        assertEquals(answer, context.answer)
                        val kinds = listOf(listOf(Kind.PDF, Kind.PNG), listOf(Kind.PNG), emptyList())[index - 1]
                        assertEquals(kinds, context.references.map { it.kind })
                        context.references.forEach { reference ->
                            val bytes = reference.bytes
                            assertTrue(bytes.isReadOnly)
                            assertContentEquals(if (reference.kind == Kind.PDF) pdf else png, ByteArray(bytes.remaining()).also { bytes.get(it) })
                        }
                    }
                }
                fixedTeachClarificationAdapter.respond(context)
            })
        }
        try {
            server.start(wait = false)
            val port = server.resolvedConnectors().single().port
            fun raw(bytes: ByteArray = ask, token: String? = "fixture-token", path: String = endpoint, extra: String = "") =
                begin(port, bytes, token, path, extra).use { it.getInputStream().readBytes().decodeToString() }
                    .also { completion.await() }
            expectStatus(401, raw(token = null))
            expectStatus(401, raw(token = "invalid"))
            expectStatus(403, raw(token = "other"))
            expectStatus(400, raw(path = "$endpoint?extra=1"))
            expectStatus(400, raw(extra = "Content-Type: $CLARIFICATION_MEDIA_TYPE\r\n"))
            expectStatus(415, raw(extra = "Content-Encoding: identity\r\n"))
            assertEquals(0, reads.get())
            val result = Json.parseToJsonElement(expectStatus(200, raw())).jsonObject
            assertEquals(digest, result.getValue("workbookSha256").jsonPrimitive.content)
            assertEquals(Json.parseToJsonElement("""[{"sheet":"Tarifas","a1":"B2"},{"sheet":"Tarifas","a1":"C2"}]"""), result["candidates"])
            assertEquals(setOf("kind", "workbookSha256", "candidates"), result.keys)
            admins.remove(admin)
            expectStatus(403, raw(reply()))
            assertEquals(1, reads.get())
            admins.add(admin)
            expectStatus(409, raw(reply(xlsx = clarificationWorkbook(1))))
            expectStatus(409, raw(reply(xlsx = clarificationWorkbook(1), expected = sha256(clarificationWorkbook(1)))))
            expectStatus(415, raw(reply(png = png.copyOf(png.size - 1))))
            for (count in listOf(0, 1, 3)) {
                val xlsx = clarificationWorkbook(count)
                assertEquals("""{"kind":"not_applicable"}""", expectStatus(200, raw(clarificationFrame("""[1,"ask",${xlsx.size}]""", xlsx))))
            }
            assertEquals(1, turns.get())
            for (data in listOf(reply(), reply(pdf = byteArrayOf()), reply(pdf = byteArrayOf(), png = byteArrayOf()))) {
                assertEquals("""{"kind":"acknowledged"}""", expectStatus(200, raw(data)))
            }
            assertEquals(4, turns.get())
        } finally { server.stop(0, 1_000) }
    }

    @Test fun `default-off and production routes are unmatched and non-cacheable before any body read`() {
        for ((environmentName, local) in listOf("qa" to false, "prod" to true)) testApplication {
            environment { config = MapApplicationConfig() }
            val reads = AtomicInteger()
            application {
                configurePlugins()
                receivePipeline.intercept(ApplicationReceivePipeline.Before) { reads.incrementAndGet() }
                mount(InMemoryAdminUsersRepository(), environmentName, local)
            }
            val response = client.post(endpoint) { upload(ask) }
            assertEquals(HttpStatusCode.NotFound, response.status)
            assertEquals(listOf("no-store"), response.headers.getAll(HttpHeaders.CacheControl))
            assertEquals(0, reads.get())
        }
    }

    @Test fun `hidden or unverified coordinates cannot acknowledge a reply and inspection keeps its old output`() = testApplication {
        environment { config = MapApplicationConfig() }
        val turns = AtomicInteger()
        application {
            configurePlugins()
            mount(InMemoryAdminUsersRepository().apply { add(admin) }, adapter = TeachClarificationAdapter {
                turns.incrementAndGet()
                fixedTeachClarificationAdapter.respond(it)
            })
        }
        val confirmed = client.post(endpoint) { upload(ask) }
        assertEquals(HttpStatusCode.OK, confirmed.status)
        val prior = Json.parseToJsonElement(confirmed.bodyAsText()).jsonObject.getValue("workbookSha256").jsonPrimitive.content
        val excluded = listOf<(String) -> String>(
            { it.clarificationColumns("min=\"3\" max=\"5\" hidden=\"1\"") },
            { it.replace("""<row r="2">""", """<row r="2" hidden="1">""") },
            { it.clarificationColumns("min=\"2\" max=\"3\" hidden=\"true\"", "min=\"3\" max=\"4\" hidden=\"false\"") },
            { it.clarificationColumns("min=\"4\" max=\"3\" hidden=\"true\"") },
            { it.clarificationColumns("min=\"16384\" max=\"16385\" hidden=\"true\"") },
            { it.clarificationColumns("min=\"3\" max=\"3\" hidden=\"unknown\"") },
            { it.replace("""<row r="3">""", """<row r="3" hidden="unknown">""") },
            { it.replace("""<row r="3">""", """<row r="3" collapsed="1">""") },
            { it.replace("<sheetData>", """<sheetFormatPr defaultRowHeight="15" zeroHeight="1"/><sheetData>""") },
            { it.replace("</sheetData>", """</sheetData><cols><col min="3" max="3" hidden="1"/></cols>""") },
            { it.replace("<sheetData>", """<mc:AlternateContent xmlns:mc="http://schemas.openxmlformats.org/markup-compatibility/2006"><mc:Fallback><cols><col min="3" max="3" hidden="1"/></cols></mc:Fallback></mc:AlternateContent><sheetData>""") },
        )
        for ((index, edit) in excluded.withIndex()) {
            val xlsx = clarificationWorkbook(editWorksheet = edit)
            val question = client.post(endpoint) { upload(clarificationFrame("""[1,"ask",${xlsx.size}]""", xlsx)) }
            assertEquals(HttpStatusCode.OK, question.status, "fixture $index")
            assertEquals("""{"kind":"not_applicable"}""", question.bodyAsText(), "fixture $index")
            for (supplied in listOf(prior, sha256(xlsx))) {
                val denied = client.post(endpoint) { upload(reply(xlsx = xlsx, expected = supplied)) }
                assertEquals(HttpStatusCode.Conflict, denied.status, "fixture $index")
                assertEquals("no-store", denied.headers[HttpHeaders.CacheControl])
            }
            val inspected = client.post("/api/v1/teach-me/inspect-workbook") {
                upload(xlsx, WORKBOOK_MEDIA_TYPE, WORKBOOK_FILENAME_HEADER, "synthetic.xlsx")
            }
            assertEquals(HttpStatusCode.OK, inspected.status, "fixture $index")
            val first = Json.parseToJsonElement(inspected.bodyAsText()).jsonObject.getValue("inspection")
                .jsonObject.getValue("sheets").jsonArray.first().jsonObject
            assertEquals(setOf("name", "state", "headers", "cells"), first.keys)
            assertEquals(listOf("A2", "B2", "C2"), first.getValue("headers").jsonArray.map { it.jsonPrimitive.content })
        }
        assertEquals(1, turns.get())
    }

    @Test fun `strict tuple UTF-8 lengths and exact end are enforced before invoking the adapter`() = testApplication {
        environment { config = MapApplicationConfig() }
        val calls = AtomicInteger()
        application {
            configurePlugins()
            mount(InMemoryAdminUsersRepository().apply { add(admin) }, adapter = TeachClarificationAdapter {
                calls.incrementAndGet()
                fixedTeachClarificationAdapter.respond(it)
            })
        }
        suspend fun status(bytes: ByteArray) = client.post(endpoint) { upload(bytes) }.status.value
        val malformed = listOf(
            byteArrayOf(), ByteArray(4), ask.copyOf(3), ask.copyOf(ask.size - 1), ask + 1.toByte(),
            clarificationFrame(byteArrayOf(0xc3.toByte(), 0x28)),
            clarificationFrame("""[1,"ask","${workbook.size}"]""", workbook),
            clarificationFrame("""[1,ask,${workbook.size}]""", workbook),
            clarificationFrame("""[1,"ask",${workbook.size},]""", workbook),
            clarificationFrame("""[1,"ask",${workbook.size} 0]""", workbook),
            clarificationFrame("""[1,"ask",0]"""),
            clarificationFrame("""[1,"ask",1.0]"""),
            clarificationFrame("""[1,"ask",-1]"""),
            clarificationFrame("""[1,"ask",1,"extra"]"""),
            clarificationFrame("""[1,"ask",[1]]"""),
            clarificationFrame("""[1,"reply",${workbook.size},0,0,"$digest","\uD800"]""", workbook),
            clarificationFrame("""[1,"reply",${workbook.size},0,0,"$digest","before${'\t'}after"]""", workbook),
            reply(answer = " \u0085\u00a0"), reply(expected = "A".repeat(64)),
        )
        for ((index, bytes) in malformed.withIndex()) assertEquals(400, status(bytes), "malformed case $index")
        val excessive = listOf(
            ByteBuffer.allocate(4).putInt(-1).array(), ByteBuffer.allocate(4).putInt(32 * 1024 + 1).array(),
            clarificationFrame("""[1,"ask",${WORKBOOK_BODY_LIMIT + 1}]"""),
            clarificationFrame("""[1,"ask",99999999999999999999999999999]"""),
            clarificationFrame("""[1,"reply",1,${REFERENCE_BODY_LIMIT + 1},0,"$digest","ok"]"""),
            clarificationFrame("""[1,"reply",1,0,${REFERENCE_BODY_LIMIT + 1},"$digest","ok"]"""),
            reply(answer = "😀".repeat(1024) + "a"),
        )
        for (bytes in excessive) assertEquals(413, status(bytes))
        assertEquals(0, calls.get())
        val fullBook = clarificationWorkbook(size = WORKBOOK_BODY_LIMIT)
        val fullPdf = pdf + ByteArray(REFERENCE_BODY_LIMIT - pdf.size) { ' '.code.toByte() }
        val fullPng = syntheticReferencePngOfSize(REFERENCE_BODY_LIMIT)
        val manifest = manifest(fullBook, fullPdf, fullPng, "😀".repeat(1024), sha256(fullBook))
        val full = clarificationFrame(manifest.padEnd(manifest.length + 32 * 1024 - manifest.encodeToByteArray().size), fullBook, fullPdf, fullPng)
        assertEquals(9 * 1024 * 1024 + 32 * 1024 + 4, full.size)
        assertEquals(200, status(full))
        assertEquals(413, status(full + 0.toByte()))
        assertEquals(1, calls.get())
    }

    @Test fun `wrong adapter decision is a generic not-cacheable 500`() = testApplication {
        environment { config = MapApplicationConfig() }
        application {
            configurePlugins()
            mount(InMemoryAdminUsersRepository().apply { add(admin) }, adapter = TeachClarificationAdapter { TeachClarificationAdapter.Decision.ACKNOWLEDGE_REPLY })
        }
        val response = client.post(endpoint) { upload(ask) }
        assertEquals(HttpStatusCode.InternalServerError, response.status)
        assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
        val text = response.bodyAsText()
        assertContains(text, "Internal server error")
        assertFalse("Tarifas" in text || "ACKNOWLEDGE_REPLY" in text)
    }

    @Test fun `clarification shares upload permits with the other three routes and recovers`() = testApplication {
        environment { config = MapApplicationConfig() }
        val release = CompletableDeferred<Unit>()
        val admitted = CompletableDeferred<Unit>()
        val reads = AtomicInteger()
        application {
            configurePlugins()
            receivePipeline.intercept(ApplicationReceivePipeline.Before) {
                if (reads.incrementAndGet() == 4) admitted.complete(Unit)
                release.await()
            }
            routing { route("/api/v1") { teachWorkbookMessageRoutes(clarificationEnabled = true) { Allowed } } }
        }
        val routes = listOf("clarifications", "references", "messages", "inspect-workbook")
        suspend fun post(path: String) = client.post("/api/v1/teach-me/$path") {
            when (path) {
                "clarifications" -> upload(ask)
                "references" -> upload(png, "image/png", REFERENCE_FILENAME_HEADER, "synthetic.png")
                else -> upload(workbook, WORKBOOK_MEDIA_TYPE, WORKBOOK_FILENAME_HEADER, "synthetic.xlsx")
            }
        }
        coroutineScope {
            val holders = routes.map { async { post(it) } }
            try {
                withTimeout(3_000) { admitted.await() }
                routes.forEach { assertEquals(HttpStatusCode.TooManyRequests, post(it).status) }
                assertEquals(4, reads.get())
            } finally { release.complete(Unit) }
            assertTrue(holders.awaitAll().all { it.status == HttpStatusCode.OK })
        }
        assertEquals(HttpStatusCode.OK, post("clarifications").status)
    }

    @Test fun `one admitted deadline covers local adapter and observed close suppresses late success`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val closed = CompletableDeferred<Unit>()
        val completed = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val slow = AtomicBoolean(true)
        val server = server {
            configurePlugins()
            intercept(ApplicationCallPipeline.Plugins) {
                if (call.request.header(HttpHeaders.Authorization) != "Bearer disconnect") return@intercept
                (call as NettyApplicationCall).context.channel().closeFuture().addListener { closed.complete(Unit) }
                try { proceed() } finally { completed.complete(Unit) }
            }
            routing { route("/api/v1") {
                teachWorkbookMessageRoutes(timeoutMillis = 800, clarificationEnabled = true, clarificationAdapter = TeachClarificationAdapter {
                    if (slow.getAndSet(false)) {
                        entered.complete(Unit)
                        check(release.await(4, TimeUnit.SECONDS))
                    } else if (it.answer == "slow fixture") Thread.sleep(950)
                    fixedTeachClarificationAdapter.respond(it)
                }) { Allowed }
            } }
        }
        try {
            server.start(wait = false)
            val port = server.resolvedConnectors().single().port
            begin(port, ask, "disconnect").use { withTimeout(3_000) { entered.await() } }
            withTimeout(3_000) { closed.await() }
            assertFalse(completed.isCompleted)
            release.countDown()
            withTimeout(3_000) { completed.await() }
            fun raw(bytes: ByteArray) = begin(port, bytes).use { it.getInputStream().readBytes().decodeToString() }
            expectStatus(408, raw(reply(answer = "slow fixture")))
            expectStatus(200, raw(ask))
        } finally { release.countDown(); server.stop(0, 1_000) }
    }

    @Test fun `blocked authorizations across four routes are not queued or freed by request timeout`() = testApplication {
        environment { config = MapApplicationConfig() }
        val entered = CountDownLatch(4)
        val release = CountDownLatch(1)
        val workersDone = CountDownLatch(4)
        val reads = AtomicInteger()
        application {
            configurePlugins()
            receivePipeline.intercept(ApplicationReceivePipeline.Before) { reads.incrementAndGet() }
            routing { route("/api/v1") {
                teachWorkbookMessageRoutes(timeoutMillis = 800, clarificationEnabled = true) { token ->
                    if (token == "hold") {
                        entered.countDown()
                        try { check(release.await(5, TimeUnit.SECONDS)) } finally { workersDone.countDown() }
                    }
                    Allowed
                }
            } }
        }
        suspend fun post(path: String, token: String = "fixture-token") = client.post("/api/v1/teach-me/$path") {
            header(HttpHeaders.Authorization, "Bearer $token")
            header(HttpHeaders.ContentType, CLARIFICATION_MEDIA_TYPE)
            setBody(ask)
        }
        coroutineScope {
            val holders = listOf("clarifications", "references", "messages", "inspect-workbook").map { async { post(it, "hold") } }
            try {
                assertTrue(withContext(Dispatchers.IO) { entered.await(3, TimeUnit.SECONDS) })
                assertEquals(HttpStatusCode.TooManyRequests, post("clarifications").status)
                assertTrue(holders.awaitAll().all { it.status == HttpStatusCode.RequestTimeout })
                assertEquals(HttpStatusCode.TooManyRequests, post("clarifications").status)
                assertEquals(0, reads.get())
            } finally { release.countDown() }
            assertTrue(withContext(Dispatchers.IO) { workersDone.await(3, TimeUnit.SECONDS) })
        }
        // A thread finishing its decision may not yet be waiting on the direct handoff.
        val success = withTimeout(3_000) {
            var status = post("clarifications").status
            while (status == HttpStatusCode.TooManyRequests) { yield(); status = post("clarifications").status }
            status
        }
        assertEquals(HttpStatusCode.OK, success)
        assertEquals(1, reads.get())
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "BM_TEACH_CLARIFY_TEST_WEB", matches = ".+")
    fun `candidate Pages proxy and Ktor share the same two synthetic turns over loopback`() = runBlocking {
        val jointWorkbook = clarificationWorkbook(3) { it.clarificationColumns("min=\"4\" max=\"4\" hidden=\"1\"") }
        val turns = AtomicInteger()
        val server = server {
            configurePlugins()
            mount(InMemoryAdminUsersRepository().apply { add(admin) }, adapter = TeachClarificationAdapter { context ->
                assertTrue(context.candidates == coordinates)
                if (turns.getAndIncrement() == 0) {
                    assertTrue(context.answer == null && context.references.isEmpty())
                } else {
                    assertTrue(context.answer == answer)
                    assertTrue(context.references.map { it.kind } == listOf(Kind.PDF, Kind.PNG))
                    context.references.zip(listOf(pdf, png)).forEach { (reference, expected) ->
                        val bytes = reference.bytes
                        assertTrue(bytes.isReadOnly && ByteArray(bytes.remaining()).also { bytes.get(it) }.contentEquals(expected))
                    }
                }
                fixedTeachClarificationAdapter.respond(context)
            })
        }
        try {
            server.start(wait = false)
            val base64 = Base64.getEncoder()
            runTeachPagesLoopback("chains actual Pages handler over HTTP to Ktor", mapOf(
                "BM_TEACH_CLARIFY_TEST_PORT" to server.resolvedConnectors().single().port.toString(),
                "BM_TEACH_CLARIFY_TEST_WORKBOOK" to base64.encodeToString(jointWorkbook),
                "BM_TEACH_CLARIFY_TEST_PDF" to base64.encodeToString(pdf),
                "BM_TEACH_CLARIFY_TEST_PNG" to base64.encodeToString(png),
            ))
            assertEquals(2, turns.get(), "Two authorized requests reached the candidate adapter")
        } finally {
            server.stop(0, 1_000)
        }
    }

    private fun HttpRequestBuilder.upload(bytes: ByteArray, mediaType: String = CLARIFICATION_MEDIA_TYPE,
                                          filenameHeader: String? = null, filename: String = "") {
        header(HttpHeaders.Authorization, "Bearer fixture-token")
        header(HttpHeaders.ContentType, mediaType)
        if (filenameHeader != null) header(filenameHeader, Base64.getUrlEncoder().withoutPadding().encodeToString(filename.toByteArray()))
        setBody(bytes)
    }
}
