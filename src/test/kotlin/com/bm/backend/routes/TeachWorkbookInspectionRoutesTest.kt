package com.bm.backend.routes

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.bm.backend.API_GLOBAL_RATE_LIMIT
import com.bm.backend.API_RATE_LIMIT
import com.bm.backend.API_RATE_LIMIT_PER_CALLER
import com.bm.backend.configurePlugins
import com.bm.backend.models.TeachWorkbookAuthDecision.Allowed
import com.bm.backend.models.TeachWorkbookAuthDecision.InvalidToken
import com.bm.backend.services.AdminAccessControlService
import com.bm.backend.services.TeachMeAccessPolicy
import com.bm.backend.services.TeachWorkbookValidator
import com.bm.backend.testing.*
import com.google.firebase.ErrorCode
import com.google.firebase.auth.AuthErrorCode
import com.google.firebase.auth.FirebaseAuthException
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.http.content.OutgoingContent
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.*
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.engine.applicationEngineEnvironment
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationCall
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.request.ApplicationReceivePipeline
import io.ktor.server.request.header
import io.ktor.server.response.ApplicationSendPipeline
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.*
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipFile
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.time.Instant
import java.util.Base64
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

private const val INSPECT = "/api/v1/teach-me/inspect-workbook"
private const val MESSAGE = "/api/v1/teach-me/messages"
private const val REFERENCE = "/api/v1/teach-me/references"
private const val ADMIN = "admin@example.invalid"
private val uploadAliases = listOf(
    "/api//v1/teach-me/%69nspect-workbook",
    "/api/v1//teach-me/%6Dessages",
    "/api/v1/teach-me//%72eferences",
)

class TeachWorkbookInspectionRoutesTest {
    private val workbook = syntheticZip(inspectionParts())
    private val filename = "sintético.xlsx"
    private fun wire(value: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray())

    private fun HttpRequestBuilder.upload(bytes: ByteArray, token: String = "test", reference: Boolean = false) {
        header(HttpHeaders.Authorization, "Bearer $token")
        header(HttpHeaders.ContentType, if (reference) "image/png" else WORKBOOK_MEDIA_TYPE)
        header(if (reference) REFERENCE_FILENAME_HEADER else WORKBOOK_FILENAME_HEADER, wire(if (reference) "sample.png" else filename))
        setBody(object : OutgoingContent.WriteChannelContent() {
            override suspend fun writeTo(channel: ByteWriteChannel) { channel.writeFully(bytes) }
        })
    }

    @Test fun `Netty loopback invokes mounted QA authorization and returns deterministic closed JSON`() = runBlocking {
        val reads = AtomicInteger()
        val admin = InMemoryAdminUsersRepository().apply { add(ADMIN); add("other@example.invalid") }
        val server = embeddedServer(Netty, applicationEngineEnvironment {
            config = MapApplicationConfig()
            connector { host = "127.0.0.1"; port = 0 }
            module {
                install(ContentNegotiation) { json() }
                receivePipeline.intercept(ApplicationReceivePipeline.Before) { reads.incrementAndGet() }
                routing { route("/api/v1") {
                    teachMeAccessRoutes(AdminAccessControlService(admin), TeachMeAccessPolicy("qa", ADMIN)) { token ->
                        if (token == "invalid") throw FirebaseAuthException(ErrorCode.UNAUTHENTICATED, "private detail", null, null, AuthErrorCode.INVALID_ID_TOKEN)
                        val email = if (token == "other") "other@example.invalid" else ADMIN
                        AuthenticatedFirebaseUser("test-user", email, null, Instant.EPOCH, Instant.MAX)
                    }
                } }
            }
        })
        try {
            server.start(wait = false)
            val port = server.resolvedConnectors().single().port
            fun raw(token: String? = "test", bytes: ByteArray = workbook, extra: String = "", chunked: Boolean = false) =
                begin(port, token, bytes, extra, chunked).use { it.getInputStream().readBytes().decodeToString() }
            for ((token, code) in listOf(null to 401, "invalid" to 401, "other" to 403)) assertStatus(code, raw(token))
            val duplicateName = raw(extra = "$WORKBOOK_FILENAME_HEADER: ${wire(filename)}\r\n")
            assertStatus(400, duplicateName)
            assertContains(duplicateName, "invalid_message")
            assertStatus(400, raw(extra = "Content-Type: $WORKBOOK_MEDIA_TYPE\r\n"))
            assertEquals(0, reads.get())

            val first = raw(chunked = true)
            assertStatus(200, first)
            assertContains(first.lowercase(), "content-type: application/json; charset=utf-8")
            val payload = first.substringAfter("\r\n\r\n")
            assertEquals(payload, raw().substringAfter("\r\n\r\n"))
            assertContains(payload, "\"filename\":\"$filename\",\"sizeBytes\":${workbook.size}")
            assertContains(payload, "\"headers\":[\"A2\",\"AA2\"]")
            assertContains(payload, "\"a1\":\"E3\",\"kind\":\"formula\"}")
            assertFalse("1+1" in payload || "example.invalid" in payload)

            val exact = raw(bytes = inspectionWorkbookOfSize(5_242_880), chunked = true)
            assertStatus(200, exact)
            assertContains(exact, "\"sizeBytes\":5242880")
            val excess = raw(bytes = inspectionWorkbookOfSize(5_242_881), chunked = true)
            assertStatus(413, excess)
            assertEquals("""{"error":"upload_too_large"}""", excess.substringAfter("\r\n\r\n"))

            val unsupported = raw(bytes = syntheticZip())
            assertStatus(415, unsupported)
            assertContains(unsupported, "unsupported_workbook")
            assertFalse(filename in unsupported || "inspection\"" in unsupported)
            admin.remove(ADMIN)
            assertStatus(403, raw())
            assertEquals(5, reads.get())
        } finally { server.stop(0, 1_000) }
    }

    @Test fun `production plugin prevents caching of unmatched upload paths outside QA`() = testApplication {
        environment { config = MapApplicationConfig() }
        val reads = AtomicInteger()
        application {
            configurePlugins()
            receivePipeline.intercept(ApplicationReceivePipeline.Before) { reads.incrementAndGet() }
            routing { route("/api/v1") {
                teachMeAccessRoutes(AdminAccessControlService(InMemoryAdminUsersRepository()), TeachMeAccessPolicy("prod", ADMIN)) {
                    error("non-QA must not verify auth")
                }
            } }
        }
        for (path in listOf(INSPECT, MESSAGE, REFERENCE, "$INSPECT/") + uploadAliases) {
            val response = client.post(path) { upload(workbook) }
            assertEquals(HttpStatusCode.NotFound, response.status, path)
            assertEquals(listOf("no-store"), response.headers.getAll(HttpHeaders.CacheControl), path)
            assertFalse(filename in response.bodyAsText())
        }
        // An encoded slash remains inside its segment; unrelated paths are not upload requests.
        val unrelated = client.post("/api/v1/teach-me%2Finspect-workbook") { upload(workbook) }
        assertEquals(HttpStatusCode.NotFound, unrelated.status)
        assertNull(unrelated.headers[HttpHeaders.CacheControl])
        assertEquals(0, reads.get())
    }

    @Test fun `Netty production plugins sanitize internal failures and cover rate limits on routed upload aliases`() = runBlocking {
        val calls = AtomicInteger()
        val reads = AtomicInteger()
        val privateDetail = "private synthetic dependency detail"
        val token = "synthetic-test-bearer"
        val logger = LoggerFactory.getLogger("test.teach-upload-plugin-guard") as Logger
        val appender = ListAppender<ILoggingEvent>().apply {
            list = Collections.synchronizedList(mutableListOf())
            start()
        }
        logger.addAppender(appender)
        val server = embeddedServer(Netty, applicationEngineEnvironment {
            config = MapApplicationConfig()
            log = logger
            connector { host = "127.0.0.1"; port = 0 }
            module {
                configurePlugins()
                receivePipeline.intercept(ApplicationReceivePipeline.Before) { reads.incrementAndGet() }
                routing { rateLimit(API_GLOBAL_RATE_LIMIT) { rateLimit(API_RATE_LIMIT) { route("/api/v1") {
                    teachWorkbookMessageRoutes {
                        if (calls.incrementAndGet() <= uploadAliases.size) error(privateDetail)
                        InvalidToken
                    }
                } } } }
            }
        })
        try {
            server.start(wait = false)
            val port = server.resolvedConnectors().single().port
            fun raw(path: String, reference: Boolean = false): String =
                begin(port, token, if (reference) syntheticReferencePng() else workbook, path = path, reference = reference)
                    .use { it.getInputStream().readBytes().decodeToString() }
            for ((index, path) in uploadAliases.withIndex()) {
                val response = raw(path, reference = index == 2)
                assertStatus(500, response)
                assertContains(response, "Internal server error")
                for (detail in listOf(privateDetail, token, filename)) assertFalse(detail in response)
            }
            repeat(API_RATE_LIMIT_PER_CALLER - uploadAliases.size) { assertStatus(401, raw(INSPECT)) }
            for ((index, path) in uploadAliases.withIndex()) assertStatus(429, raw(path, reference = index == 2))
            assertEquals(API_RATE_LIMIT_PER_CALLER, calls.get())
            assertEquals(0, reads.get())
            val events = synchronized(appender.list) { appender.list.toList() }
            val errors = events.filter { it.level == ch.qos.logback.classic.Level.ERROR }
            assertEquals(List(uploadAliases.size) { "Unhandled Enséñame upload failure" }, errors.map { it.formattedMessage })
            assertTrue(errors.all { it.throwableProxy == null })
            assertTrue(events.none { privateDetail in it.formattedMessage || token in it.formattedMessage || filename in it.formattedMessage })
        } finally {
            server.stop(0, 1_000)
            logger.detachAppender(appender)
            appender.stop()
        }
    }

    @Test fun `actual lengthless body boundary is inclusive and receipt grammar stays separate`() = testApplication {
        environment { config = MapApplicationConfig() }
        application {
            install(ContentNegotiation) { json() }
            routing { route("/api/v1") { teachWorkbookMessageRoutes { Allowed } } }
        }
        val exact = inspectionWorkbookOfSize(5_242_880)
        assertEquals(5_242_880, exact.size)
        val ok = client.post(INSPECT) { upload(exact) }
        assertEquals(HttpStatusCode.OK, ok.status)
        assertContains(ok.bodyAsText(), "\"sizeBytes\":5242880")
        val excess = client.post(INSPECT) { upload(inspectionWorkbookOfSize(5_242_881)) }
        assertEquals(HttpStatusCode.PayloadTooLarge, excess.status)
        assertEquals("""{"error":"upload_too_large"}""", excess.bodyAsText())
        assertEquals(HttpStatusCode.BadRequest, client.post("$INSPECT?unexpected=yes") { upload(workbook) }.status)
        assertEquals(HttpStatusCode.BadRequest, client.post(INSPECT) { upload(byteArrayOf()) }.status)
        assertEquals(HttpStatusCode.UnsupportedMediaType, client.post(INSPECT) {
            upload(workbook)
            header(HttpHeaders.ContentEncoding, "identity")
        }.status)
        val old = syntheticZip()
        assertEquals(HttpStatusCode.UnsupportedMediaType, client.post(INSPECT) { upload(old) }.status)
        val receipt = client.post(MESSAGE) { upload(old) }
        assertEquals(HttpStatusCode.OK, receipt.status)
        assertFalse("inspection" in receipt.bodyAsText())
    }

    @Test fun `ZIP XML and encoded response budgets return only the generic error`() = testApplication {
        environment { config = MapApplicationConfig() }
        application {
            install(ContentNegotiation) { json() }
            routing { route("/api/v1") {
                teachWorkbookMessageRoutes(validator = TeachWorkbookValidator(maxInflated = 512 * 1024)) { Allowed }
            } }
        }
        val overZip = syntheticZip(inspectionParts().apply { put("opaque.txt", ByteArray(512 * 1024)) })
        val overXml = syntheticZip(inspectionParts().apply { put(FIRST_SHEET, getValue(FIRST_SHEET) + ByteArray(256 * 1024) { 32 }) })
        val manyEscapes = (1..40).joinToString("") { row ->
            """<row r="$row"><c r="A$row" t="str"><v>${"&quot;".repeat(900)}</v></c></row>"""
        }
        val overResponse = syntheticZip(inspectionParts().apply { put(FIRST_SHEET, worksheet(manyEscapes).toByteArray()) })
        for (bytes in listOf(overZip, overXml, overResponse)) {
            val result = client.post(INSPECT) { upload(bytes) }
            assertEquals(HttpStatusCode.PayloadTooLarge, result.status)
            assertEquals("""{"error":"upload_too_large"}""", result.bodyAsText())
            assertEquals("no-store", result.headers[HttpHeaders.CacheControl])
        }
        assertEquals(HttpStatusCode.OK, client.post(INSPECT) { upload(workbook) }.status)
    }

    @Test fun `all three POSTs share four upload permits and rejected calls never open the body`() = testApplication {
        environment { config = MapApplicationConfig() }
        val release = CompletableDeferred<Unit>()
        val admitted = CompletableDeferred<Unit>()
        val reads = AtomicInteger()
        application {
            install(ContentNegotiation) { json() }
            receivePipeline.intercept(ApplicationReceivePipeline.Before) {
                if (reads.incrementAndGet() == 4) admitted.complete(Unit)
                release.await()
            }
            routing { route("/api/v1") { teachWorkbookMessageRoutes { Allowed } } }
        }
        coroutineScope {
            val held = listOf(INSPECT, MESSAGE, REFERENCE, INSPECT).map { endpoint ->
                async { client.post(endpoint) { upload(if (endpoint == REFERENCE) syntheticReferencePng() else workbook, reference = endpoint == REFERENCE) } }
            }
            try {
                withTimeout(3_000) { admitted.await() }
                for (endpoint in listOf(INSPECT, MESSAGE, REFERENCE)) {
                    assertEquals(HttpStatusCode.TooManyRequests, client.post(endpoint) { upload(workbook) }.status)
                }
                assertEquals(4, reads.get())
            } finally { release.complete(Unit) }
            assertTrue(held.awaitAll().all { it.status == HttpStatusCode.OK })
        }
        assertEquals(HttpStatusCode.OK, client.post(INSPECT) { upload(workbook) }.status)
    }

    @Test fun `single deadline checkpoints abandon inspection work and return the permit`() = testApplication {
        environment { config = MapApplicationConfig() }
        val slow = AtomicBoolean(true)
        val entered = AtomicInteger()
        val validator = trackingValidator { entry ->
            if (entry == FIRST_SHEET && slow.getAndSet(false)) {
                entered.incrementAndGet()
                Thread.sleep(800) // Controlled synchronous work: still holds its permit until returning.
            }
        }
        application {
            install(ContentNegotiation) { json() }
            routing { route("/api/v1") { teachWorkbookMessageRoutes(validator, timeoutMillis = 500) { Allowed } } }
        }
        val timed = client.post(INSPECT) { upload(workbook) }
        assertEquals(HttpStatusCode.RequestTimeout, timed.status)
        assertEquals(1, entered.get())
        assertFalse(filename in timed.bodyAsText())
        assertEquals(HttpStatusCode.OK, client.post(INSPECT) { upload(workbook) }.status)
    }

    @Test fun `Netty observed close during inspection exits without sending success and permits are reusable`() = runBlocking {
        val paused = AtomicBoolean()
        val entered = CompletableDeferred<Unit>()
        val closeObserved = CompletableDeferred<Unit>()
        val exited = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val holdersReady = CompletableDeferred<Unit>()
        val releaseHolders = CompletableDeferred<Unit>()
        val holdersAdmitted = AtomicInteger()
        val holders = mutableListOf<Socket>()
        val successResponses = AtomicInteger()
        val opaqueReads = AtomicInteger()
        val validator = trackingValidator { entry ->
            if (entry == FIRST_SHEET && !paused.getAndSet(true)) {
                entered.complete(Unit)
                check(release.await(5, TimeUnit.SECONDS))
            }
            if (entry == "xl/tables/_rels/first.xml.rels") opaqueReads.incrementAndGet()
        }
        val server = embeddedServer(Netty, applicationEngineEnvironment {
            config = MapApplicationConfig()
            connector { host = "127.0.0.1"; port = 0 }
            module {
                install(ContentNegotiation) { json() }
                intercept(ApplicationCallPipeline.Plugins) {
                    if (call.request.header(HttpHeaders.Authorization) != "Bearer disconnect") return@intercept
                    (call as NettyApplicationCall).context.channel().closeFuture().addListener { closeObserved.complete(Unit) }
                    try { proceed() } finally { exited.complete(Unit) }
                }
                sendPipeline.intercept(ApplicationSendPipeline.Before) {
                    if (call.request.header(HttpHeaders.Authorization) == "Bearer disconnect" &&
                        (subject as? OutgoingContent)?.status == HttpStatusCode.OK) successResponses.incrementAndGet()
                }
                receivePipeline.intercept(ApplicationReceivePipeline.Before) {
                    if (call.request.header(HttpHeaders.Authorization) == "Bearer hold") {
                        if (holdersAdmitted.incrementAndGet() == 3) holdersReady.complete(Unit)
                        releaseHolders.await()
                    }
                }
                routing { route("/api/v1") { teachWorkbookMessageRoutes(validator) { Allowed } } }
            }
        })
        try {
            server.start(wait = false)
            val port = server.resolvedConnectors().single().port
            begin(port, "disconnect", workbook).use {
                withTimeout(3_000) { entered.await() }
            }
            withTimeout(3_000) { closeObserved.await() }
            assertFalse(exited.isCompleted)
            repeat(3) { holders.add(begin(port, "hold", workbook)) }
            withTimeout(3_000) { holdersReady.await() }
            fun probe() = begin(port, "test", workbook).use { it.getInputStream().readBytes().decodeToString() }
            assertStatus(429, probe()) // Close was observed, but synchronous work still owns the fourth permit.
            release.countDown()
            withTimeout(3_000) { exited.await() }
            assertEquals(0, successResponses.get())
            assertEquals(0, opaqueReads.get())
            assertStatus(200, probe()) // Only the cancelled inspection's permit can have become available.
            assertEquals(1, opaqueReads.get())
        } finally {
            release.countDown()
            releaseHolders.complete(Unit)
            holders.forEach(Socket::close)
            server.stop(0, 1_000)
        }
    }

    @Suppress("DEPRECATION")
    private fun trackingValidator(read: (String) -> Unit) = TeachWorkbookValidator(open = { bytes, size, _ ->
        object : ZipFile(SeekableInMemoryByteChannel(bytes).truncate(size.toLong())) {
            override fun getRawInputStream(entry: ZipArchiveEntry): InputStream? {
                read(entry.name)
                return super.getRawInputStream(entry)
            }
        }
    })

    private fun assertStatus(code: Int, response: String) {
        assertTrue(response.startsWith("HTTP/1.1 $code "), response.substringBefore("\r\n"))
        assertContains(response.lowercase(), "cache-control: no-store")
    }

    private fun begin(port: Int, token: String?, bytes: ByteArray, extra: String = "", chunked: Boolean = false,
                      path: String = INSPECT, reference: Boolean = false) = Socket().apply {
        soTimeout = 3_000
        connect(InetSocketAddress("127.0.0.1", port), 2_000)
        getOutputStream().apply {
            val auth = token?.let { "Authorization: Bearer $it\r\n" }.orEmpty()
            val framing = if (chunked) "Transfer-Encoding: chunked" else "Content-Length: ${bytes.size}"
            val type = if (reference) "image/png" else WORKBOOK_MEDIA_TYPE
            val fileHeader = if (reference) "$REFERENCE_FILENAME_HEADER: ${wire("sample.png")}" else "$WORKBOOK_FILENAME_HEADER: ${wire(filename)}"
            write(("POST $path HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n" +
                "${auth}Content-Type: $type\r\n$fileHeader\r\n$extra$framing\r\n\r\n").toByteArray())
            if (chunked) write("${bytes.size.toString(16)}\r\n".toByteArray())
            write(bytes)
            if (chunked) write("\r\n0\r\n\r\n".toByteArray())
            flush()
        }
    }
}
