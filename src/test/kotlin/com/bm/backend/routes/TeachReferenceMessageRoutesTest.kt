package com.bm.backend.routes

import com.bm.backend.API_RATE_LIMIT
import com.bm.backend.API_RATE_LIMIT_PER_CALLER
import com.bm.backend.configurePlugins
import com.bm.backend.models.TeachWorkbookAuthDecision.Allowed
import com.bm.backend.services.AdminAccessControlService
import com.bm.backend.services.TeachMeAccessPolicy
import com.bm.backend.testing.InMemoryAdminUsersRepository
import com.bm.backend.testing.syntheticReferencePdf
import com.bm.backend.testing.syntheticReferencePng
import com.bm.backend.testing.syntheticReferencePngOfSize
import com.bm.backend.testing.syntheticZip
import com.google.firebase.ErrorCode
import com.google.firebase.auth.AuthErrorCode
import com.google.firebase.auth.FirebaseAuthException
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.http.content.OutgoingContent
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.request.ApplicationReceivePipeline
import io.ktor.server.routing.*
import io.ktor.server.testing.TestApplicationEngine
import io.ktor.server.testing.createTestEnvironment
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class TeachReferenceMessageRoutesTest {
    private val references = "/teach-me/references"
    private val messages = "/teach-me/messages"
    private val filename = "Apoyo España.PnG"
    private val nominated = "nominated@example.invalid"
    private fun wire(name: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(name.encodeToByteArray())
    private fun HttpRequestBuilder.upload(
        bytes: ByteArray, type: String? = "image/png", name: String? = wire(filename),
        nameHeader: String = REFERENCE_FILENAME_HEADER, token: String? = "test",
    ) {
        token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
        type?.let { header(HttpHeaders.ContentType, it) }
        name?.let { header(nameHeader, it) }
        setBody(object : OutgoingContent.WriteChannelContent() {
            override suspend fun writeTo(channel: ByteWriteChannel) { channel.writeFully(bytes) }
        })
    }

    @Test fun `authorizes each raw upload and returns only matching PDF and PNG receipts`() = testApplication {
        environment { config = MapApplicationConfig() }
        val reads = AtomicInteger()
        val admins = InMemoryAdminUsersRepository().apply { add(nominated); add("other@example.invalid") }
        application {
            install(ContentNegotiation) { json() }
            receivePipeline.intercept(ApplicationReceivePipeline.Before) { reads.incrementAndGet() }
            routing {
                teachMeAccessRoutes(AdminAccessControlService(admins), TeachMeAccessPolicy("qa", nominated)) { token ->
                    if (token == "invalid") throw FirebaseAuthException(
                        ErrorCode.UNAUTHENTICATED, "private provider details", null, null, AuthErrorCode.INVALID_ID_TOKEN,
                    )
                    val email = if (token == "other") "other@example.invalid" else nominated
                    AuthenticatedFirebaseUser("test-uid", email, null, Instant.EPOCH, Instant.MAX)
                }
            }
        }
        val png = syntheticReferencePng()
        assertEquals(HttpStatusCode.Unauthorized, client.post(references) { upload(png, token = null) }.status)
        val invalid = client.post(references) { upload(png, token = "invalid") }
        assertEquals(HttpStatusCode.Unauthorized, invalid.status)
        assertFalse("private provider details" in invalid.bodyAsText())
        assertEquals(HttpStatusCode.Forbidden, client.post(references) { upload(png, token = "other") }.status)
        assertEquals(0, reads.get())
        for ((bytes, type, name, kind) in listOf(
            ReceiptCase(png, "IMAGE/PNG", filename, "reference_png"),
            ReceiptCase(syntheticReferencePdf(), "Application/PDF", "Apoyo.PDF", "reference_pdf"),
        )) {
            val response = client.post(references) { upload(bytes, type = type, name = wire(name)) }
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
            assertEquals(buildJsonObject {
                putJsonObject("attachment") {
                    put("kind", kind); put("filename", name); put("sizeBytes", bytes.size)
                }
            }, Json.parseToJsonElement(response.bodyAsText()))
        }
        admins.remove(nominated)
        assertEquals(HttpStatusCode.Forbidden, client.post(references) { upload(png) }.status)
        assertEquals(HttpStatusCode.Forbidden, client.post(messages) { upload(syntheticZip(), WORKBOOK_MEDIA_TYPE,
            wire("example.xlsx"), WORKBOOK_FILENAME_HEADER) }.status)
        assertEquals(2, reads.get())
    }

    private data class ReceiptCase(val bytes: ByteArray, val type: String, val name: String, val kind: String)

    @Test fun `production has no reference route and application still prevents caching`() = testApplication {
        environment { config = MapApplicationConfig() }
        val reads = AtomicInteger()
        application {
            configurePlugins()
            receivePipeline.intercept(ApplicationReceivePipeline.Before) { reads.incrementAndGet() }
            routing { route("/api/v1") {
                teachMeAccessRoutes(AdminAccessControlService(InMemoryAdminUsersRepository()), TeachMeAccessPolicy("prod", nominated)) {
                    error("Authorization must not run outside QA")
                }
            } }
        }
        val response = client.post("/api/v1$references") { upload(syntheticReferencePng()) }
        assertEquals(HttpStatusCode.NotFound, response.status)
        assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
        assertEquals(0, reads.get())
    }

    @Test fun `application rate limit and unexpected errors are non cacheable and sanitized`() = testApplication {
        environment { config = MapApplicationConfig() }
        val reads = AtomicInteger()
        val calls = AtomicInteger()
        application {
            configurePlugins()
            receivePipeline.intercept(ApplicationReceivePipeline.Before) { reads.incrementAndGet() }
            routing { route("/api/v1") { rateLimit(API_RATE_LIMIT) {
                teachWorkbookMessageRoutes { calls.incrementAndGet(); error("private dependency detail") }
            } } }
        }
        repeat(API_RATE_LIMIT_PER_CALLER + 1) { index ->
            val response = client.post("/api/v1$references") { upload(syntheticReferencePng()) }
            assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
            if (index == API_RATE_LIMIT_PER_CALLER) assertEquals(HttpStatusCode.TooManyRequests, response.status)
            if (index == 0) {
                assertEquals(HttpStatusCode.InternalServerError, response.status)
                assertFalse("private dependency detail" in response.bodyAsText())
            }
        }
        assertTrue(calls.get() <= API_RATE_LIMIT_PER_CALLER)
        assertEquals(0, reads.get())
    }

    @Test fun `rejects invalid metadata before reads and checks empty or renamed bodies`() = testApplication {
        environment { config = MapApplicationConfig() }
        val reads = AtomicInteger()
        application {
            install(ContentNegotiation) { json() }
            receivePipeline.intercept(ApplicationReceivePipeline.Before) { reads.incrementAndGet() }
            routing { teachWorkbookMessageRoutes { Allowed } }
        }
        val png = syntheticReferencePng()
        val good = wire(filename)
        for (invalid in listOf(null, "$good=", "$good, $good", "_w", "Zh", wire("\uFEFFa.png"), wire("a\u202Ex.png"),
            wire("dir/a.png"), wire(" .png"), wire("foo.pdf"), wire("é".repeat(126) + ".png"))) {
            assertEquals(HttpStatusCode.BadRequest, client.post(references) { upload(png, name = invalid) }.status, invalid)
        }
        assertEquals(HttpStatusCode.BadRequest, client.post(references) { upload(png, nameHeader = WORKBOOK_FILENAME_HEADER) }.status)
        assertEquals(HttpStatusCode.BadRequest, client.post(references) { upload(png, type = "image/png, image/png") }.status)
        assertEquals(HttpStatusCode.BadRequest, client.post("$references?extra=yes") { upload(png) }.status)
        for (type in listOf(null, "image/png; charset=utf-8", WORKBOOK_MEDIA_TYPE)) {
            val response = client.post(references) { upload(png, type = type) }
            assertEquals(HttpStatusCode.UnsupportedMediaType, response.status)
            assertEquals(buildJsonObject { put("error", "unsupported_reference") }, Json.parseToJsonElement(response.bodyAsText()))
        }
        assertEquals(HttpStatusCode.UnsupportedMediaType, client.post(references) {
            upload(png); header(HttpHeaders.ContentEncoding, "identity")
        }.status)
        assertEquals(0, reads.get())
        val empty = client.post(references) { upload(byteArrayOf()) }
        assertEquals(HttpStatusCode.BadRequest, empty.status)
        assertEquals(buildJsonObject { put("error", "invalid_message") }, Json.parseToJsonElement(empty.bodyAsText()))
        assertEquals(HttpStatusCode.UnsupportedMediaType, client.post(references) { upload(syntheticZip()) }.status)
        for (reference in listOf(png, syntheticReferencePdf())) {
            assertEquals(HttpStatusCode.UnsupportedMediaType, client.post(messages) {
                upload(reference, WORKBOOK_MEDIA_TYPE, wire("example.xlsx"), WORKBOOK_FILENAME_HEADER)
            }.status)
        }
        assertEquals(HttpStatusCode.OK, client.post(references) { upload(png, name = wire("é".repeat(125) + "a.png")) }.status)
        assertEquals(5, reads.get())
    }

    @Test fun `duplicate raw type or filename lines are rejected before open`() {
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
            for ((name, value) in listOf(HttpHeaders.ContentType to "image/png", REFERENCE_FILENAME_HEADER to wire(filename))) {
                val call = engine.handleRequest {
                    uri = references
                    method = HttpMethod.Post
                    addHeader(HttpHeaders.Authorization, "Bearer test")
                    addHeader(REFERENCE_FILENAME_HEADER, wire(filename))
                    addHeader(HttpHeaders.ContentType, "image/png")
                    addHeader(name.lowercase(), value)
                }
                assertEquals(HttpStatusCode.BadRequest, call.response.status())
                assertEquals(0, reads.get())
            }
        } finally { engine.stop(0, 0) }
    }

    @Test fun `actual lengthless bytes allow exactly 2 MiB and reject one excess byte`() = testApplication {
        environment { config = MapApplicationConfig() }
        application { install(ContentNegotiation) { json() }; routing { teachWorkbookMessageRoutes { Allowed } } }
        val png = syntheticReferencePngOfSize(REFERENCE_BODY_LIMIT)
        val exact = client.post(references) { upload(png) }
        assertEquals(HttpStatusCode.OK, exact.status)
        assertEquals(REFERENCE_BODY_LIMIT, Json.parseToJsonElement(exact.bodyAsText()).jsonObject.getValue("attachment")
            .jsonObject.getValue("sizeBytes").jsonPrimitive.int)
        val excess = client.post(references) { upload(png + byteArrayOf(0)) }
        assertEquals(HttpStatusCode.PayloadTooLarge, excess.status)
        assertEquals(buildJsonObject { put("error", "upload_too_large") }, Json.parseToJsonElement(excess.bodyAsText()))
    }

    @Test fun `reference and workbook share the same four upload permits and release after completion`() = testApplication {
        environment { config = MapApplicationConfig() }
        val all = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val reads = AtomicInteger()
        application {
            install(ContentNegotiation) { json() }
            receivePipeline.intercept(ApplicationReceivePipeline.Before) {
                if (reads.incrementAndGet() == 4) all.complete(Unit)
                release.await()
            }
            routing { teachWorkbookMessageRoutes { Allowed } }
        }
        val png = syntheticReferencePng()
        val workbook = syntheticZip()
        coroutineScope {
            val pending = List(4) { index -> async {
                if (index % 2 == 0) client.post(references) { upload(png) }.status
                else client.post(messages) { upload(workbook, WORKBOOK_MEDIA_TYPE, wire("example.xlsx"), WORKBOOK_FILENAME_HEADER) }.status
            } }
            try {
                withTimeout(3_000) { all.await() }
                assertEquals(HttpStatusCode.TooManyRequests, client.post(references) { upload(png) }.status)
                assertEquals(4, reads.get())
            } finally { release.complete(Unit) }
            assertTrue(pending.awaitAll().all { it == HttpStatusCode.OK })
            assertEquals(HttpStatusCode.OK, client.post(references) { upload(png) }.status)
        }
    }

    @Test fun `reference authorization time is deducted from the deadline and the next reference can proceed`() = testApplication {
        environment { config = MapApplicationConfig() }
        val started = CompletableDeferred<Unit>()
        val unblock = CountDownLatch(1)
        val reads = AtomicInteger()
        application {
            install(ContentNegotiation) { json() }
            receivePipeline.intercept(ApplicationReceivePipeline.Before) { reads.incrementAndGet() }
            routing { teachWorkbookMessageRoutes(timeoutMillis = 1_300) { token ->
                if (token == "hold") { started.complete(Unit); unblock.await() }
                Allowed
            } }
        }
        coroutineScope {
            val pending = async {
                client.post(references) {
                    upload(byteArrayOf(), token = "hold")
                    setBody(object : OutgoingContent.WriteChannelContent() {
                        override suspend fun writeTo(channel: ByteWriteChannel) { awaitCancellation() }
                    })
                }
            }
            try {
                withTimeout(800) { started.await() }
                delay(700)
                unblock.countDown()
                assertEquals(HttpStatusCode.RequestTimeout, withTimeout(950) { pending.await() }.status)
                assertEquals(1, reads.get())
                assertEquals(HttpStatusCode.OK, client.post(references) { upload(syntheticReferencePng()) }.status)
            } finally { unblock.countDown() }
        }
    }
}
