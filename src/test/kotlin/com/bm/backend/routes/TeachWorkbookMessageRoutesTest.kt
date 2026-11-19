package com.bm.backend.routes

import com.bm.backend.models.TeachWorkbookAuthDecision.Allowed
import com.bm.backend.services.AdminAccessControlService
import com.bm.backend.services.TeachMeAccessPolicy
import com.bm.backend.services.TeachWorkbookAuthWorkers
import com.bm.backend.services.TeachWorkbookValidator
import com.bm.backend.testing.InMemoryAdminUsersRepository
import com.bm.backend.testing.bookParts
import com.bm.backend.testing.inspectionParts
import com.bm.backend.testing.syntheticWorkbookOfSize
import com.bm.backend.testing.syntheticReferencePng
import com.bm.backend.testing.syntheticZip
import com.google.firebase.ErrorCode
import com.google.firebase.auth.AuthErrorCode
import com.google.firebase.auth.FirebaseAuthException
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.http.content.OutgoingContent
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.engine.applicationEngineEnvironment
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationCall
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.ApplicationReceivePipeline
import io.ktor.server.request.header
import io.ktor.server.routing.routing
import io.ktor.server.testing.TestApplicationEngine
import io.ktor.server.testing.createTestEnvironment
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.apache.commons.compress.archivers.zip.ZipFile
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel
import java.net.InetSocketAddress
import java.net.Socket
import java.time.Instant
import java.util.Base64
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.Test
import kotlin.test.*

class TeachWorkbookMessageRoutesTest {
    private val email = "admin@example.invalid"
    private val path = "/teach-me/messages"
    private val filename = "Ejemplo español.XLSX"
    private fun wire(value: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray())
    private fun HttpRequestBuilder.upload(bytes: ByteArray, token: String? = "test", name: String? = wire(filename), type: String? = WORKBOOK_MEDIA_TYPE) {
        token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
        name?.let { header(WORKBOOK_FILENAME_HEADER, it) }
        type?.let { header(HttpHeaders.ContentType, it) }
        // WriteChannelContent has no declared length. No multipart or intermediate data model.
        setBody(object : OutgoingContent.WriteChannelContent() {
            override suspend fun writeTo(channel: ByteWriteChannel) { channel.writeFully(bytes) }
        })
    }

    @Test fun `authorizes before opening the raw body and checks live revocation`() = testApplication {
        environment { config = MapApplicationConfig() }
        val reads = AtomicInteger()
        val admins = InMemoryAdminUsersRepository().apply { add(email) }
        application {
            install(ContentNegotiation) { json() }
            receivePipeline.intercept(ApplicationReceivePipeline.Before) { reads.incrementAndGet() }
            routing {
                teachMeAccessRoutes(AdminAccessControlService(admins), TeachMeAccessPolicy("qa", email)) { token ->
                    if (token != "test") throw FirebaseAuthException(ErrorCode.UNAUTHENTICATED, "private provider details", null, null, AuthErrorCode.INVALID_ID_TOKEN)
                    AuthenticatedFirebaseUser("test-uid", email, null, Instant.EPOCH, Instant.MAX)
                }
            }
        }
        val bytes = syntheticZip()
        assertEquals(HttpStatusCode.Unauthorized, client.post(path) { upload(bytes, token = null) }.status)
        val invalid = client.post(path) { upload(bytes, token = "invalid") }
        assertEquals(HttpStatusCode.Unauthorized, invalid.status)
        assertFalse("private provider details" in invalid.bodyAsText())
        assertEquals(0, reads.get())
        val response = client.post(path) { upload(bytes, type = WORKBOOK_MEDIA_TYPE.uppercase()) }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
        val root = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(setOf("attachment"), root.keys)
        val attachment = root.getValue("attachment").jsonObject
        assertEquals(setOf("kind", "filename", "sizeBytes"), attachment.keys)
        assertEquals("workbook", attachment.getValue("kind").jsonPrimitive.content)
        assertEquals(filename, attachment.getValue("filename").jsonPrimitive.content)
        assertEquals(bytes.size, attachment.getValue("sizeBytes").jsonPrimitive.int)
        admins.remove(email)
        assertEquals(HttpStatusCode.Forbidden, client.post(path) { upload(bytes) }.status)
        assertEquals(1, reads.get())
    }

    @Test fun `outside QA is absent and missing or changed applied nominations cannot open the body`() {
        for ((environmentName, nominated, status) in listOf(
            Triple("prod", email, HttpStatusCode.NotFound), Triple("qa", null, HttpStatusCode.Forbidden),
            Triple("qa", "someone-else@example.invalid", HttpStatusCode.Forbidden),
        )) testApplication {
            environment { config = MapApplicationConfig() }
            val reads = AtomicInteger()
            application {
                install(ContentNegotiation) { json() }
                receivePipeline.intercept(ApplicationReceivePipeline.Before) { reads.incrementAndGet() }
                routing {
                    teachMeAccessRoutes(AdminAccessControlService(InMemoryAdminUsersRepository().apply { add(email) }),
                        TeachMeAccessPolicy(environmentName, nominated)) {
                        AuthenticatedFirebaseUser("test-uid", email, null, Instant.EPOCH, Instant.MAX)
                    }
                }
            }
            assertEquals(status, client.post(path) { upload(syntheticZip()) }.status)
            assertEquals(0, reads.get())
        }
    }

    @Test fun `rejects invalid raw headers before reading and invalid raw payloads after reading`() = testApplication {
        environment { config = MapApplicationConfig() }
        val reads = AtomicInteger()
        application {
            install(ContentNegotiation) { json() }
            receivePipeline.intercept(ApplicationReceivePipeline.Before) { reads.incrementAndGet() }
            routing { teachWorkbookMessageRoutes { Allowed } }
        }
        val bytes = syntheticZip()
        val good = wire(filename)
        for (invalid in listOf(null, "$good=", "$good, $good", "_w", "Zh", wire("\uFEFFa.xlsx"),
            wire("a\u202Ex.xlsx"), wire("dir/a.xlsx"), wire(".xlsx"), wire("${"a".repeat(251)}.xlsx"))) {
            assertEquals(HttpStatusCode.BadRequest, client.post(path) { upload(bytes, name = invalid) }.status, invalid)
        }
        assertEquals(HttpStatusCode.BadRequest, client.post(path) { upload(bytes); header(WORKBOOK_FILENAME_HEADER, good) }.status)
        assertEquals(HttpStatusCode.BadRequest, client.post(path) { upload(bytes, type = "$WORKBOOK_MEDIA_TYPE, $WORKBOOK_MEDIA_TYPE") }.status)
        assertEquals(HttpStatusCode.BadRequest, client.post("$path?text=ignored") { upload(bytes) }.status)
        assertEquals(HttpStatusCode.UnsupportedMediaType, client.post(path) { upload(bytes, type = null) }.status)
        assertEquals(HttpStatusCode.UnsupportedMediaType, client.post(path) { upload(bytes, type = "$WORKBOOK_MEDIA_TYPE; charset=utf-8") }.status)
        assertEquals(HttpStatusCode.UnsupportedMediaType, client.post(path) { upload(bytes); header(HttpHeaders.ContentEncoding, "identity") }.status)
        assertEquals(0, reads.get())
        val empty = client.post(path) { upload(byteArrayOf()) }
        assertEquals(HttpStatusCode.BadRequest, empty.status)
        assertEquals("invalid_message", Json.parseToJsonElement(empty.bodyAsText()).jsonObject.getValue("error").jsonPrimitive.content)
        assertEquals(HttpStatusCode.UnsupportedMediaType, client.post(path) { upload("synthetic text".toByteArray()) }.status)
        assertEquals(2, reads.get())
        val exactName = "${"é".repeat(125)}.xlsx"
        assertEquals(HttpStatusCode.OK, client.post(path) { upload(bytes, name = wire(exactName)) }.status)
    }

    @Test fun `rejects separately repeated raw type before reading any body`() {
        // The high-level Ktor client intentionally normalizes this singleton header.
        val reads = AtomicInteger()
        val engine = TestApplicationEngine(createTestEnvironment {
            config = MapApplicationConfig()
            module {
                install(ContentNegotiation) { json() }
                receivePipeline.intercept(ApplicationReceivePipeline.Before) { reads.incrementAndGet() }
                routing { teachWorkbookMessageRoutes { Allowed } }
            }
        })
        engine.start()
        try {
            val call = engine.handleRequest {
                uri = path
                method = HttpMethod.Post
                addHeader(HttpHeaders.Authorization, "Bearer test")
                addHeader(WORKBOOK_FILENAME_HEADER, wire(filename))
                addHeader(HttpHeaders.ContentType, WORKBOOK_MEDIA_TYPE)
                addHeader(HttpHeaders.ContentType, WORKBOOK_MEDIA_TYPE)
            }
            assertEquals(HttpStatusCode.BadRequest, call.response.status())
            assertEquals(0, reads.get())
        } finally { engine.stop(0, 0) }
    }

    @Test fun `accepts exactly 5 MiB and rejects one excess byte on lengthless streaming requests`() = testApplication {
        environment { config = MapApplicationConfig() }
        application {
            install(ContentNegotiation) { json() }
            routing { teachWorkbookMessageRoutes { Allowed } }
        }
        val exact = syntheticWorkbookOfSize(WORKBOOK_BODY_LIMIT)
        val success = client.post(path) { upload(exact) }
        assertEquals(HttpStatusCode.OK, success.status)
        assertEquals(WORKBOOK_BODY_LIMIT, Json.parseToJsonElement(success.bodyAsText()).jsonObject
            .getValue("attachment").jsonObject.getValue("sizeBytes").jsonPrimitive.int)
        val excess = client.post(path) { upload(exact + byteArrayOf(0)) }
        assertEquals(HttpStatusCode.PayloadTooLarge, excess.status)
        assertEquals(HttpStatusCode.OK, client.post(path) { upload(syntheticZip()) }.status)
    }

    @Test fun `blocked authorization keeps its worker after timeout or disconnect and never opens an abandoned upload`() = testApplication {
        environment { config = MapApplicationConfig() }
        val entered = AtomicInteger()
        val all = CompletableDeferred<Unit>()
        val one = CountDownLatch(1)
        val others = CountDownLatch(1)
        val disconnect = CompletableDeferred<Job>()
        val invoked = ConcurrentLinkedQueue<String>()
        val reads = AtomicInteger()
        val uploadTransitions = AtomicInteger()
        val readerOpens = AtomicInteger()
        val validator = TeachWorkbookValidator(open = { bytes, size, _ ->
            readerOpens.incrementAndGet()
            val channel = SeekableInMemoryByteChannel(bytes).truncate(size.toLong())
            ZipFile.builder().setSeekableByteChannel(channel).get()
        })
        application {
            install(ContentNegotiation) { json() }
            intercept(ApplicationCallPipeline.Plugins) {
                if (call.request.header(HttpHeaders.Authorization) == "Bearer disconnected") {
                    disconnect.complete(currentCoroutineContext()[Job]!!)
                }
            }
            receivePipeline.intercept(ApplicationReceivePipeline.Before) { reads.incrementAndGet() }
            routing { teachWorkbookMessageRoutes(validator, timeoutMillis = 1_000, beforeUpload = { uploadTransitions.incrementAndGet() }) { token ->
                invoked.add(token)
                if (token in setOf("expired", "disconnected", "blocked-a", "blocked-b")) {
                    if (entered.incrementAndGet() == 4) all.complete(Unit)
                    if (token == "expired") one.await() else others.await()
                }
                Allowed // Deliberately favorable, even for the abandoned callers.
            } }
        }
        val bytes = syntheticZip()
        coroutineScope {
            val expired = async { client.post(path) { upload(bytes, token = "expired") }.status }
            val disconnected = async { client.post(path) { upload(bytes, token = "disconnected") }.status }
            val blocked = listOf("blocked-a", "blocked-b").map { token -> async { client.post(path) { upload(bytes, token = token) }.status } }
            try {
                withTimeout(800) { all.await() }
                assertEquals(HttpStatusCode.Unauthorized, client.post(path) { upload(bytes, token = null) }.status)
                assertEquals(HttpStatusCode.TooManyRequests, client.post(path) { upload(bytes, token = "rejected") }.status)
                disconnect.await().cancel()
                disconnected.cancelAndJoin()
                assertEquals(HttpStatusCode.RequestTimeout, expired.await())
                assertTrue(blocked.awaitAll().all { it == HttpStatusCode.RequestTimeout })
                assertEquals(HttpStatusCode.TooManyRequests, client.post(path) { upload(bytes, token = "still-rejected") }.status)
                assertEquals(0, reads.get())
                assertEquals(0, uploadTransitions.get())
                assertEquals(0, readerOpens.get())
                assertEquals(4, invoked.size)

                one.countDown()
                // Retry using distinct requests; no rejected task is allowed to run later.
                withTimeout(1_200) {
                    var i = 0
                    var status: HttpStatusCode
                    do {
                        status = client.post(path) { upload(bytes, token = "replacement-${i++}") }.status
                        if (status == HttpStatusCode.TooManyRequests) delay(5)
                    } while (status == HttpStatusCode.TooManyRequests)
                    assertEquals(HttpStatusCode.OK, status)
                }
                assertEquals(1, reads.get())
                assertEquals(1, uploadTransitions.get())
                assertEquals(1, readerOpens.get())
                assertEquals(5, invoked.size)
                assertFalse(invoked.any { it == "rejected" || it == "still-rejected" })
            } finally {
                one.countDown()
                others.countDown()
            }
        }
    }

    @Test fun `worker shutdown rejects new submissions without detaching or replacing a blocked task`() = runBlocking {
        val started = CompletableDeferred<Thread>()
        val release = CountDownLatch(1)
        val workers = TeachWorkbookAuthWorkers {
            started.complete(Thread.currentThread())
            release.await()
            Allowed
        }
        try {
            val task = assertNotNull(workers.submit("synthetic"))
            val worker = withTimeout(1_000) { started.await() }
            assertTrue(worker.isDaemon)
            workers.close()
            assertNull(workers.submit("rejected"))
            assertFalse(task.isCompleted)
            release.countDown()
            assertEquals(Allowed, withTimeout(1_000) { task.await().getOrThrow() })
            withContext(Dispatchers.IO) { worker.join(1_000) }
            assertFalse(worker.isAlive)
        } finally {
            workers.close()
            release.countDown()
        }
    }

    @Test fun `Netty peer close abandons the workbook before a late grant without freeing the blocked worker`() = peerClose(false)

    @Test fun `Netty peer close abandons a reference before a late grant with mixed auth saturation`() = peerClose(true)

    @Test fun `Netty peer close abandons inspection before grant with all three routes sharing auth slots`() = peerClose(false, inspect = true)

    private fun peerClose(reference: Boolean, inspect: Boolean = false) = runBlocking {
        val one = CountDownLatch(1)
        val others = CountDownLatch(1)
        val admitted = AtomicInteger()
        val fourAdmitted = CompletableDeferred<Unit>()
        val peerClosed = CompletableDeferred<Unit>()
        val requestExited = CompletableDeferred<Unit>()
        val reads = AtomicInteger()
        val transitions = AtomicInteger()
        val readerOpens = AtomicInteger()
        val validator = TeachWorkbookValidator(open = { bytes, size, _ ->
            readerOpens.incrementAndGet()
            val channel = SeekableInMemoryByteChannel(bytes).truncate(size.toLong())
            ZipFile.builder().setSeekableByteChannel(channel).get()
        })
        val server = embeddedServer(Netty, applicationEngineEnvironment {
            config = MapApplicationConfig()
            connector { host = "127.0.0.1"; port = 0 }
            module {
                install(ContentNegotiation) { json() }
                intercept(ApplicationCallPipeline.Plugins) {
                    if (call.request.header(HttpHeaders.Authorization) != "Bearer disconnect") return@intercept
                    (call as NettyApplicationCall).context.channel().closeFuture().addListener { peerClosed.complete(Unit) }
                    try { proceed() } finally { requestExited.complete(Unit) }
                }
                receivePipeline.intercept(ApplicationReceivePipeline.Before) { reads.incrementAndGet() }
                routing {
                    teachWorkbookMessageRoutes(validator, beforeUpload = { transitions.incrementAndGet() }) { token ->
                        if (token == "disconnect" || token == "hold") {
                            if (admitted.incrementAndGet() == 4) fourAdmitted.complete(Unit)
                            if (token == "disconnect") one.await() else others.await()
                        }
                        Allowed
                    }
                }
            }
        })
        val sockets = mutableListOf<Socket>()
        try {
            server.start(wait = false)
            val port = server.resolvedConnectors().single().port
            val workbook = syntheticZip(if (inspect) inspectionParts() else bookParts())
            fun begin(token: String, useReference: Boolean = false, useInspection: Boolean = false): Socket = Socket().apply {
                sockets.add(this)
                soTimeout = 2_000
                connect(InetSocketAddress("127.0.0.1", port), 2_000)
                val endpoint = when {
                    useReference -> "/teach-me/references"
                    useInspection -> "/teach-me/inspect-workbook"
                    else -> path
                }
                val mediaType = if (useReference) "image/png" else WORKBOOK_MEDIA_TYPE
                val nameHeader = if (useReference) REFERENCE_FILENAME_HEADER else WORKBOOK_FILENAME_HEADER
                val bytes = if (useReference) syntheticReferencePng() else workbook
                val headers = listOf("POST $endpoint HTTP/1.1", "Host: 127.0.0.1", "Connection: close",
                    "Authorization: Bearer $token", "Content-Type: $mediaType",
                    "$nameHeader: ${wire(if (useReference) "synthetic.png" else filename)}", "Content-Length: ${bytes.size}")
                getOutputStream().apply {
                    write(headers.joinToString("\r\n", postfix = "\r\n\r\n").toByteArray(Charsets.US_ASCII))
                    // This caller disconnects during authorization, before sending its declared body.
                    if (!(inspect && token == "disconnect")) write(bytes)
                    flush()
                }
            }
            fun probe(): Int = begin("probe", useInspection = inspect).use {
                it.getInputStream().bufferedReader(Charsets.US_ASCII).readLine().split(' ')[1].toInt()
            }

            val disconnect = begin("disconnect", reference, inspect)
            repeat(3) { begin("hold", useReference = inspect && it == 0) }
            withTimeout(3_000) { fourAdmitted.await() }
            disconnect.close()
            withTimeout(3_000) {
                peerClosed.await() // Actual Netty transport notification, not synthetic Job cancellation.
                requestExited.await() // Caller exits while its fake dependency is still blocked.
            }
            assertEquals(429, probe())
            assertEquals(0, transitions.get())
            assertEquals(0, reads.get())
            assertEquals(0, readerOpens.get())

            one.countDown() // Favorable result, well before the caller's original 15-second deadline.
            withTimeout(3_000) {
                var status = probe()
                while (status == 429) { delay(5); status = probe() }
                assertEquals(200, status)
            }
            assertEquals(1, transitions.get())
            assertEquals(1, reads.get())
            assertEquals(1, readerOpens.get()) // Only the replacement reached validation.
        } finally {
            sockets.forEach(Socket::close)
            one.countDown()
            others.countDown()
            server.stop(0, 1_000)
        }
    }

    @Test fun `an approved upload needs no auth worker even when all four become busy`() = testApplication {
        environment { config = MapApplicationConfig() }
        val beforeUpload = CompletableDeferred<Unit>()
        val unblockUpload = CompletableDeferred<Unit>()
        val all = CompletableDeferred<Unit>()
        val entered = AtomicInteger()
        val release = CountDownLatch(1)
        val reads = AtomicInteger()
        application {
            install(ContentNegotiation) { json() }
            receivePipeline.intercept(ApplicationReceivePipeline.Before) { reads.incrementAndGet() }
            routing { teachWorkbookMessageRoutes(beforeUpload = { beforeUpload.complete(Unit); unblockUpload.await() }) { token ->
                if (token == "hold") {
                    if (entered.incrementAndGet() == 4) all.complete(Unit)
                    release.await()
                }
                Allowed
            } }
        }
        val bytes = syntheticZip()
        coroutineScope {
            val approved = async { client.post(path) { upload(bytes) }.status }
            withTimeout(2_000) { beforeUpload.await() }
            val held = List(4) { async { client.post(path) { upload(bytes, token = "hold") }.status } }
            try {
                withTimeout(2_000) { all.await() }
                assertEquals(HttpStatusCode.TooManyRequests, client.post(path) { upload(bytes) }.status)
                unblockUpload.complete(Unit)
                assertEquals(HttpStatusCode.OK, withTimeout(2_000) { approved.await() })
                assertEquals(1, reads.get())
            } finally {
                unblockUpload.complete(Unit)
                held.forEach { it.cancel() }
                release.countDown()
            }
        }
    }

    @Test fun `four upload permits reject a fifth approved operation without receiving the body`() = testApplication {
        environment { config = MapApplicationConfig() }
        val reads = AtomicInteger()
        val authorized = AtomicInteger()
        val all = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        application {
            install(ContentNegotiation) { json() }
            receivePipeline.intercept(ApplicationReceivePipeline.Before) {
                if (reads.incrementAndGet() == 4) all.complete(Unit)
                release.await()
            }
            routing { teachWorkbookMessageRoutes { authorized.incrementAndGet(); Allowed } }
        }
        val bytes = syntheticZip()
        coroutineScope {
            val running = List(4) { async { client.post(path) { upload(bytes) }.status } }
            try {
                withTimeout(2_000) { all.await() }
                assertEquals(HttpStatusCode.TooManyRequests, client.post(path) { upload(bytes) }.status)
                assertEquals(5, authorized.get())
                assertEquals(4, reads.get())
            } finally { release.complete(Unit) }
            assertTrue(running.awaitAll().all { it == HttpStatusCode.OK })
            assertEquals(HttpStatusCode.OK, client.post(path) { upload(bytes) }.status)
        }
    }

    @Test fun `authorization time is deducted from the single upload deadline`() = testApplication {
        environment { config = MapApplicationConfig() }
        val authStarted = CompletableDeferred<Unit>()
        val unblock = CountDownLatch(1)
        val reads = AtomicInteger()
        application {
            install(ContentNegotiation) { json() }
            receivePipeline.intercept(ApplicationReceivePipeline.Before) { reads.incrementAndGet() }
            routing { teachWorkbookMessageRoutes(timeoutMillis = 1_300) {
                authStarted.complete(Unit)
                unblock.await()
                Allowed
            } }
        }
        coroutineScope {
            val response = async {
                client.post(path) {
                    upload(byteArrayOf())
                    setBody(object : OutgoingContent.WriteChannelContent() {
                        override suspend fun writeTo(channel: ByteWriteChannel) { awaitCancellation() }
                    })
                }
            }
            try {
                withTimeout(800) { authStarted.await() }
                delay(700)
                unblock.countDown()
                assertEquals(HttpStatusCode.RequestTimeout, withTimeout(950) { response.await().status })
                assertEquals(1, reads.get())
            } finally { unblock.countDown() }
        }
    }

    @Test fun `times out during a stalled body read and returns its permit`() = testApplication {
        environment { config = MapApplicationConfig() }
        application {
            install(ContentNegotiation) { json() }
            routing { teachWorkbookMessageRoutes(timeoutMillis = 300) { Allowed } }
        }
        val response = client.post(path) {
            upload(byteArrayOf())
            setBody(object : OutgoingContent.WriteChannelContent() {
                override suspend fun writeTo(channel: ByteWriteChannel) { awaitCancellation() }
            })
        }
        assertEquals(HttpStatusCode.RequestTimeout, response.status)
        assertEquals(HttpStatusCode.OK, client.post(path) { upload(syntheticZip()) }.status)
    }

    @Test fun `checks deadline during validation and releases all four permits after validation timeouts`() = testApplication {
        environment { config = MapApplicationConfig() }
        val opened = AtomicInteger()
        val validator = TeachWorkbookValidator(open = { _, _, check ->
            opened.incrementAndGet()
            while (true) check()
            @Suppress("UNREACHABLE_CODE") error("The deadline must interrupt validation")
        })
        application {
            install(ContentNegotiation) { json() }
            routing { teachWorkbookMessageRoutes(validator, timeoutMillis = 100) { Allowed } }
        }
        val bytes = syntheticZip()
        repeat(5) { assertEquals(HttpStatusCode.RequestTimeout, client.post(path) { upload(bytes) }.status) }
        assertEquals(5, opened.get())
    }
}
