package com.bm.backend.routes

import com.bm.backend.configurePlugins
import com.bm.backend.models.TeachClarificationCandidate
import com.bm.backend.models.TeachSupplierColumnChoice
import com.bm.backend.models.TeachWorkbookAuthDecision.Allowed
import com.bm.backend.services.AdminAccessControlService
import com.bm.backend.services.TeachMeAccessPolicy
import com.bm.backend.services.TeachSupplierDraftAdapter
import com.bm.backend.services.fixedTeachSupplierDraftAdapter
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
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.time.Instant
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class TeachSupplierDraftRoutesTest {
    private val endpoint = "/api/v1/teach-me/clarifications"
    private val admin = "fixture@example.invalid"
    private val workbook = supplierDraftWorkbook()
    private val hash = sha256(workbook)
    private val ask = clarificationFrame("""[1,"ask",${workbook.size}]""", workbook)
    private val b = supplierDraftRevision(hash, "B2")
    private val c = supplierDraftRevision(hash, "C2")
    private val coords = listOf(TeachClarificationCandidate(0, "B2"), TeachClarificationCandidate(0, "C2"))

    private fun draft(answer: String = "La columna B", supplier: String = "demo-electricidad", expected: String = hash) =
        clarificationFrame("""[1,"draft",${workbook.size},"$expected",${JsonPrimitive(supplier)},${JsonPrimitive(answer)}]""", workbook)

    private fun revise(prior: String, answer: String = "La columna C") =
        clarificationFrame("""[1,"revise",${workbook.size},"$hash","demo-electricidad","$prior",${JsonPrimitive(answer)}]""", workbook)

    private fun Application.mount(
        admins: InMemoryAdminUsersRepository,
        environment: String = "qa",
        clarify: Boolean = true,
        enabled: Boolean = true,
        adapter: TeachSupplierDraftAdapter = fixedTeachSupplierDraftAdapter,
        authCount: AtomicInteger = AtomicInteger(),
    ) {
        routing { route("/api/v1") {
            teachMeAccessRoutes(AdminAccessControlService(admins), TeachMeAccessPolicy(environment, admin), clarify,
                supplierDraftEnabled = enabled, supplierDraftAdapter = adapter) { token ->
                authCount.incrementAndGet()
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

    private fun HttpRequestBuilder.upload(bytes: ByteArray, type: String = SUPPLIER_DRAFT_MEDIA_TYPE, token: String? = "fixture-token") {
        token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
        header(HttpHeaders.ContentType, type)
        setBody(bytes)
    }

    @Test fun `off and non QA reject without reading and draft flag cannot mount clarification`() {
        for ((environment, clarify, enabled) in listOf(Triple("qa", true, false), Triple("qa", false, true), Triple("prod", true, true))) {
            testApplication {
                val reads = AtomicInteger()
                this.environment { config = MapApplicationConfig() }
                application {
                    configurePlugins()
                    receivePipeline.intercept(ApplicationReceivePipeline.Before) { reads.incrementAndGet() }
                    mount(InMemoryAdminUsersRepository().apply { add(admin) }, environment, clarify, enabled)
                }
                val response = client.post(endpoint) { upload(ask) }
                assertEquals(HttpStatusCode.NotFound, response.status)
                assertEquals(listOf("no-store"), response.headers.getAll(HttpHeaders.CacheControl))
                assertEquals(0, reads.get())
                val clarification = client.post(endpoint) { upload(ask, CLARIFICATION_MEDIA_TYPE) }
                assertEquals(if (environment == "qa" && clarify) HttpStatusCode.OK else HttpStatusCode.NotFound, clarification.status)
            }
        }
    }

    @Test fun `Netty rejects incorrect authorization or raw metadata before receiving any draft frame`() = runBlocking {
        val reads = AtomicInteger()
        val completion = TeachCallCompletion()
        val admins = InMemoryAdminUsersRepository().apply { add(admin); add("other@example.invalid") }
        val server = server {
            configurePlugins()
            completion.install(this)
            receivePipeline.intercept(ApplicationReceivePipeline.Before) { reads.incrementAndGet() }
            mount(admins, adapter = TeachSupplierDraftAdapter { _, _ -> error("unexpected adapter call") })
        }
        try {
            server.start(wait = false)
            val port = server.resolvedConnectors().single().port
            fun checkStatus(code: Int, token: String? = "fixture-token", path: String = endpoint,
                            type: String = SUPPLIER_DRAFT_MEDIA_TYPE, extra: String = "", bytes: ByteArray? = null) {
                val response = Socket().use { socket ->
                    socket.soTimeout = 5_000
                    socket.connect(InetSocketAddress("127.0.0.1", port), 2_000)
                    socket.getOutputStream().apply {
                        val auth = token?.let { "Authorization: Bearer $it\r\n" }.orEmpty()
                        write(("POST $path HTTP/1.1\r\nHost: 127.0.0.1\r\n${auth}Content-Type: $type\r\n$extra" +
                            "Content-Length: ${bytes?.size ?: ask.size}\r\nConnection: close\r\n\r\n").encodeToByteArray())
                        bytes?.let { write(it) }
                        flush()
                    }
                    socket.getInputStream().readBytes().decodeToString()
                }
                assertTrue(response.startsWith("HTTP/1.1 $code "), "Expected $code: ${response.substringBefore("\r\n")}")
                assertContains(response.substringBefore("\r\n\r\n").lowercase(), "cache-control: no-store")
                completion.await()
            }
            checkStatus(401, token = null)
            checkStatus(401, token = "invalid")
            checkStatus(401, extra = "Authorization: Bearer other\r\n")
            checkStatus(403, token = "other")
            checkStatus(400, path = "$endpoint?extra=1")
            checkStatus(400, extra = "Content-Type: $SUPPLIER_DRAFT_MEDIA_TYPE\r\n")
            checkStatus(400, extra = "$WORKBOOK_FILENAME_HEADER: c2FtcGxlLnhsc3g\r\n")
            checkStatus(415, extra = "Content-Encoding: identity\r\n")
            checkStatus(415, type = "$SUPPLIER_DRAFT_MEDIA_TYPE; charset=utf-8")
            assertEquals(0, reads.get())
            checkStatus(200, bytes = ask)
            admins.remove(admin)
            checkStatus(403, bytes = draft())
            assertEquals(1, reads.get())
        } finally { server.stop(0, 1_000) }
    }

    @Test fun `strict draft tuple and exact XLSX tail are bounded without a reference transport`() = testApplication {
        environment { config = MapApplicationConfig() }
        val calls = AtomicInteger()
        application {
            configurePlugins()
            mount(InMemoryAdminUsersRepository().apply { add(admin) }, adapter = TeachSupplierDraftAdapter { candidates, choice ->
                calls.incrementAndGet()
                fixedTeachSupplierDraftAdapter.select(candidates, choice)
            })
        }
        suspend fun status(bytes: ByteArray): Int {
            val response = client.post(endpoint) { upload(bytes) }
            assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
            return response.status.value
        }
        val malformed = listOf(
            byteArrayOf(), ByteArray(4), ask.copyOf(3), ask.copyOf(ask.size - 1), ask + 1.toByte(),
            clarificationFrame(byteArrayOf(0xc3.toByte(), 0x28)), clarificationFrame("""[1,"ask",0]"""),
            clarificationFrame("""[1,"ask","${workbook.size}"]""", workbook), clarificationFrame("""[1,"ask",1.0]"""),
            clarificationFrame("""[1,"ask",[1]]"""), clarificationFrame("""[1,"ask",1,]"""),
            clarificationFrame("""[1,"draft",${workbook.size},"$hash","La columna B"]""", workbook),
            clarificationFrame("""[1,"draft",${workbook.size},"$hash","demo-electricidad","\uD800"]""", workbook),
            clarificationFrame("""[1,"reply",${workbook.size},0,0,"$hash","La columna B"]""", workbook),
            clarificationFrame("""[1,"revise",${workbook.size},"$hash","demo-electricidad","$b","La columna C",0]""", workbook),
            draft() + syntheticReferencePdf(), draft(answer = "La columna D"), draft(answer = "La columna B y C"),
            draft(answer = "\tLa columna B"), draft(answer = "La columna B\u00a0"), draft(supplier = "otro"), draft(supplier = ""),
            draft(expected = "A".repeat(64)), revise("a"), revise(c),
        )
        for ((index, bytes) in malformed.withIndex()) assertEquals(400, status(bytes), "malformed case $index")
        for (bytes in listOf(ByteBuffer.allocate(4).putInt(-1).array(), draft(answer = "a".repeat(4097)),
            clarificationFrame("""[1,"ask",${WORKBOOK_BODY_LIMIT + 1}]"""), clarificationFrame("""[1,"ask",999999999999999999999999]"""))) {
            assertEquals(413, status(bytes))
        }
        assertEquals(409, status(draft(expected = "0".repeat(64))))
        assertEquals(409, status(revise("0".repeat(64))))
        val maximum = clarificationFrame("""[1,"ask",$WORKBOOK_BODY_LIMIT]""".padEnd(32 * 1024), ByteArray(WORKBOOK_BODY_LIMIT))
        assertEquals(409, status(maximum)) // On-budget bytes, but not the approved workbook.
        assertEquals(413, status(maximum + 0.toByte()))
        assertEquals(0, calls.get())
        assertEquals(200, status(draft("  La columna B ")))
        assertEquals(200, status(revise(b)))
        assertEquals(200, status(revise(c, "La columna B")))
        assertEquals(3, calls.get())
    }

    @Test fun `adapter disagreement becomes a generic non cacheable 500`() = testApplication {
        environment { config = MapApplicationConfig() }
        application {
            configurePlugins()
            mount(InMemoryAdminUsersRepository().apply { add(admin) }, adapter = TeachSupplierDraftAdapter { _, _ -> 1 })
        }
        val response = client.post(endpoint) { upload(draft()) }
        assertEquals(HttpStatusCode.InternalServerError, response.status)
        assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
        val text = response.bodyAsText()
        assertContains(text, "Internal server error")
        assertFalse("Tarifas" in text || hash in text || b in text)
    }

    @Test fun `draft and clarification share four auth workers and four upload permits across timeouts`() = testApplication {
        environment { config = MapApplicationConfig() }
        val heldAuth = CountDownLatch(4)
        val releaseAuth = CountDownLatch(1)
        val heldUpload = CompletableDeferred<Unit>()
        val releaseUpload = CompletableDeferred<Unit>()
        val reads = AtomicInteger()
        application {
            configurePlugins()
            receivePipeline.intercept(ApplicationReceivePipeline.Before) {
                if (reads.incrementAndGet() == 4) heldUpload.complete(Unit)
                releaseUpload.await()
            }
            routing { route("/api/v1") {
                teachWorkbookMessageRoutes(timeoutMillis = 1_000, clarificationEnabled = true, supplierDraftEnabled = true) { token ->
                    if (token == "hold") {
                        heldAuth.countDown()
                        check(releaseAuth.await(7, TimeUnit.SECONDS))
                    }
                    Allowed
                }
            } }
        }
        suspend fun post(draftWire: Boolean, token: String = "fixture-token") = client.post(endpoint) {
            upload(ask, if (draftWire) SUPPLIER_DRAFT_MEDIA_TYPE else CLARIFICATION_MEDIA_TYPE, token)
        }.status
        coroutineScope {
            val auth = List(4) { async { post(it % 2 == 0, "hold") } }
            try {
                assertTrue(withContext(Dispatchers.IO) { heldAuth.await(3, TimeUnit.SECONDS) })
                assertEquals(HttpStatusCode.TooManyRequests, post(true))
                assertTrue(auth.awaitAll().all { it == HttpStatusCode.RequestTimeout })
                assertEquals(HttpStatusCode.TooManyRequests, post(false))
                assertEquals(0, reads.get())
            } finally { releaseAuth.countDown() }
            val uploads = List(4) { index -> async {
                withTimeout(3_000) {
                    var status: HttpStatusCode
                    do { status = post(index % 2 == 0); if (status == HttpStatusCode.TooManyRequests) yield() }
                    while (status == HttpStatusCode.TooManyRequests)
                    status
                }
            } }
            try {
                withTimeout(3_000) { heldUpload.await() }
                assertEquals(HttpStatusCode.TooManyRequests, post(true))
                assertEquals(HttpStatusCode.TooManyRequests, post(false))
                assertEquals(4, reads.get())
                assertTrue(uploads.awaitAll().all { it == HttpStatusCode.RequestTimeout })
            } finally { releaseUpload.complete(Unit) }
        }
        assertEquals(HttpStatusCode.OK, post(true))
    }

    @Test fun `Netty observes close during draft adapter and single deadline covers adapter work`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val closed = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val server = server {
            configurePlugins()
            intercept(ApplicationCallPipeline.Plugins) {
                if (call.request.header(HttpHeaders.Authorization) != "Bearer disconnect") return@intercept
                (call as NettyApplicationCall).context.channel().closeFuture().addListener { closed.complete(Unit) }
                try { proceed() } finally { finished.complete(Unit) }
            }
            routing { route("/api/v1") {
                teachWorkbookMessageRoutes(timeoutMillis = 800, clarificationEnabled = true, supplierDraftEnabled = true,
                    supplierDraftAdapter = TeachSupplierDraftAdapter { coords, choice ->
                        when (calls.incrementAndGet()) {
                            1 -> { entered.complete(Unit); check(release.await(4, TimeUnit.SECONDS)) }
                            2 -> Thread.sleep(950)
                        }
                        fixedTeachSupplierDraftAdapter.select(coords, choice)
                    }) { Allowed }
            } }
        }
        try {
            server.start(wait = false)
            val port = server.resolvedConnectors().single().port
            fun begin(token: String) = Socket().apply {
                soTimeout = 5_000
                connect(InetSocketAddress("127.0.0.1", port), 2_000)
                val bytes = draft()
                getOutputStream().apply {
                    write(("POST $endpoint HTTP/1.1\r\nHost: 127.0.0.1\r\nAuthorization: Bearer $token\r\n" +
                        "Content-Type: $SUPPLIER_DRAFT_MEDIA_TYPE\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").encodeToByteArray())
                    write(bytes)
                    flush()
                }
            }
            begin("disconnect").use { withTimeout(3_000) { entered.await() } }
            withTimeout(3_000) { closed.await() }
            assertFalse(finished.isCompleted)
            release.countDown()
            withTimeout(3_000) { finished.await() }
            for (status in listOf(408, 200)) {
                val raw = begin("fixture-token").use { it.getInputStream().readBytes().decodeToString() }
                assertTrue(raw.startsWith("HTTP/1.1 $status "), raw.substringBefore("\r\n"))
                assertContains(raw.substringBefore("\r\n\r\n").lowercase(), "cache-control: no-store")
            }
            assertEquals(3, calls.get())
        } finally { release.countDown(); server.stop(0, 1_000) }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "BM_TEACH_CLARIFY_TEST_WEB", matches = ".+")
    fun `candidate Pages proxy and Ktor draft and revise over loopback`() = runBlocking {
        val reads = AtomicInteger()
        val authCount = AtomicInteger()
        val selections = AtomicInteger()
        val admins = InMemoryAdminUsersRepository().apply { add(admin) }
        val server = server {
            configurePlugins()
            receivePipeline.intercept(ApplicationReceivePipeline.Before) { reads.incrementAndGet() }
            mount(admins, adapter = TeachSupplierDraftAdapter { candidates, choice ->
                assertTrue(candidates == coords)
                assertTrue(choice == listOf(TeachSupplierColumnChoice.B, TeachSupplierColumnChoice.C)[selections.getAndIncrement()])
                if (choice == TeachSupplierColumnChoice.C) admins.remove(admin)
                fixedTeachSupplierDraftAdapter.select(candidates, choice)
            }, authCount = authCount)
        }
        try {
            server.start(wait = false)
            runTeachPagesLoopback("chains supplier draft Pages proxy and Ktor over loopback", mapOf(
                "BM_TEACH_DRAFT_TEST_PORT" to server.resolvedConnectors().single().port.toString(),
                "BM_TEACH_DRAFT_TEST_WORKBOOK" to Base64.getEncoder().encodeToString(workbook),
            ))
            assertEquals(5, authCount.get())
            assertEquals(4, reads.get(), "Revoked request must not open the body")
            assertEquals(2, selections.get(), "Only authorized, continuous draft and correction reach the adapter")
        } finally { server.stop(0, 1_000) }
    }
}
