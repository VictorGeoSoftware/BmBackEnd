package com.bm.backend.routes

import com.bm.backend.configurePlugins
import com.bm.backend.models.TeachWorkbookAuthDecision.Allowed
import com.bm.backend.services.AdminAccessControlService
import com.bm.backend.services.TeachMeAccessPolicy
import com.bm.backend.services.TeachClarificationAdapter
import com.bm.backend.services.TeachSupplierDraftAdapter
import com.bm.backend.testing.*
import com.google.firebase.ErrorCode
import com.google.firebase.auth.AuthErrorCode
import com.google.firebase.auth.FirebaseAuthException
import io.ktor.http.HttpHeaders
import io.ktor.server.application.*
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.engine.applicationEngineEnvironment
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.request.ApplicationReceivePipeline
import io.ktor.server.request.header
import io.ktor.server.routing.*
import kotlinx.coroutines.*
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

class TeachDraftPreviewRoutesTest {
    private val endpoint = "/api/v1/teach-me/clarifications"
    private val admin = "fixture@example.invalid"
    private val workbook = previewWorkbook()
    private val prepare = clarificationFrame("""[1,"prepare",${workbook.size},"$PREVIEW_FIXTURE_PROFILE"]""", workbook)
    private fun preview(supplier: String = "gesternova", digest: String = PREVIEW_FIXTURE_HASH, revision: String = PREVIEW_FIXTURE_REVISION) =
        clarificationFrame("""[1,"preview",${workbook.size},"$PREVIEW_FIXTURE_PROFILE","$supplier","$digest","$revision"]""", workbook)

    private fun server(module: Application.() -> Unit) = embeddedServer(Netty, applicationEngineEnvironment {
        config = MapApplicationConfig()
        connector { host = "127.0.0.1"; port = 0 }
        module(module)
    })

    private fun Application.mount(
        admins: InMemoryAdminUsersRepository,
        enabled: Boolean = true,
        environment: String = "qa",
        draft: Boolean = true,
        clarify: Boolean = true,
        authCount: AtomicInteger = AtomicInteger(),
    ) {
        routing { route("/api/v1") {
            teachMeAccessRoutes(AdminAccessControlService(admins), TeachMeAccessPolicy(environment, admin), clarify,
                clarificationAdapter = TeachClarificationAdapter { error("unexpected clarification adapter call") },
                supplierDraftEnabled = draft, supplierDraftAdapter = TeachSupplierDraftAdapter { _, _ -> error("unexpected draft adapter call") },
                draftPreviewEnabled = enabled) { token ->
                authCount.incrementAndGet()
                if (token != "fixture-token" && token != "other") throw FirebaseAuthException(
                    ErrorCode.UNAUTHENTICATED, "private fixture detail", null, null, AuthErrorCode.INVALID_ID_TOKEN,
                )
                AuthenticatedFirebaseUser("fixture", if (token == "other") "other@example.invalid" else admin, null, Instant.EPOCH, Instant.MAX)
            }
        } }
    }

    private fun begin(port: Int, bytes: ByteArray? = null, token: String? = "fixture-token", path: String = endpoint,
                      type: String = DRAFT_PREVIEW_MEDIA_TYPE, extra: String = ""): Socket = Socket().apply {
        soTimeout = 8_000
        connect(InetSocketAddress("127.0.0.1", port), 2_000)
        getOutputStream().apply {
            val auth = token?.let { "Authorization: Bearer $it\r\n" }.orEmpty()
            write(("POST $path HTTP/1.1\r\nHost: 127.0.0.1\r\n${auth}Content-Type: $type\r\n$extra" +
                "Content-Length: ${bytes?.size ?: prepare.size}\r\nConnection: close\r\n\r\n").encodeToByteArray())
            bytes?.let { write(it) }
            flush()
        }
    }

    private fun Socket.expect(code: Int): String = use {
        val raw = getInputStream().readBytes().decodeToString()
        assertTrue(raw.startsWith("HTTP/1.1 $code "), "Expected $code: ${raw.substringBefore("\r\n")}")
        assertContains(raw.substringBefore("\r\n\r\n").lowercase(), "cache-control: no-store")
        raw.substringAfter("\r\n\r\n")
    }

    @Test fun `Netty guard and metadata reject before body and revocation suppresses preview`() = runBlocking {
        val reads = AtomicInteger()
        val admins = InMemoryAdminUsersRepository().apply { add(admin); add("other@example.invalid") }
        val server = server {
            configurePlugins()
            receivePipeline.intercept(ApplicationReceivePipeline.Before) { reads.incrementAndGet() }
            mount(admins)
        }
        try {
            server.start(wait = false)
            val port = server.resolvedConnectors().single().port
            begin(port, token = null).expect(401)
            begin(port, token = "invalid").expect(401)
            begin(port, token = "other").expect(403)
            begin(port, extra = "Authorization: Bearer other\r\n").expect(401)
            begin(port, path = "$endpoint?preview=1").expect(400)
            begin(port, extra = "Content-Type: $DRAFT_PREVIEW_MEDIA_TYPE\r\n").expect(400)
            begin(port, extra = "$REFERENCE_FILENAME_HEADER: eA\r\n").expect(400)
            begin(port, extra = "$WORKBOOK_FILENAME_HEADER: eA\r\n").expect(400)
            begin(port, extra = "Content-Encoding: identity\r\n").expect(415)
            begin(port, type = "$DRAFT_PREVIEW_MEDIA_TYPE; charset=utf-8").expect(415)
            assertEquals(0, reads.get())
            assertContains(begin(port, prepare).expect(200), PREVIEW_FIXTURE_REVISION)
            admins.remove(admin)
            begin(port).expect(403)
            assertEquals(1, reads.get())
        } finally { server.stop(0, 1_000) }
    }

    @Test fun `preview flag requires QA and both previous opt-ins`() = runBlocking {
        for ((environment, flags) in listOf(
            "qa" to Triple(true, true, false), "qa" to Triple(true, false, true),
            "qa" to Triple(false, true, true), "prod" to Triple(true, true, true),
        )) {
            val (clarify, draft, preview) = flags
            val reads = AtomicInteger()
            val checks = AtomicInteger()
            val server = server {
                configurePlugins()
                receivePipeline.intercept(ApplicationReceivePipeline.Before) { reads.incrementAndGet() }
                mount(InMemoryAdminUsersRepository(), preview, environment, draft, clarify, checks)
            }
            try {
                server.start(wait = false)
                begin(server.resolvedConnectors().single().port).expect(404)
                assertEquals(0, reads.get())
                assertEquals(0, checks.get())
            } finally { server.stop(0, 1_000) }
        }
    }

    @Test fun `Netty strictly bounds prefix tuple and tail and checks workbook and revision`() = runBlocking {
        val server = server { configurePlugins(); mount(InMemoryAdminUsersRepository().apply { add(admin) }) }
        try {
            server.start(wait = false)
            val port = server.resolvedConnectors().single().port
            for (bytes in listOf(prepare.copyOf(3), prepare.copyOf(prepare.size - 1), prepare + 1.toByte(),
                clarificationFrame(byteArrayOf(0xc3.toByte(), 0x28)), clarificationFrame("""[1,"prepare",0,"$PREVIEW_FIXTURE_PROFILE"]"""),
                clarificationFrame("""[1,"prepare",1.0,"$PREVIEW_FIXTURE_PROFILE"]"""),
                clarificationFrame("""[1,"prepare",1,"$PREVIEW_FIXTURE_PROFILE",]"""),
                clarificationFrame("""[1,"ask",${workbook.size},"$PREVIEW_FIXTURE_PROFILE"]""", workbook),
                clarificationFrame("""[1,"prepare",${workbook.size},"$PREVIEW_FIXTURE_PROFILE",{}]""", workbook),
                clarificationFrame("""[1,"prepare",${workbook.size},"\uD800"]""", workbook), preview(revision = "z"))) {
                begin(port, bytes).expect(400)
            }
            for (bytes in listOf(ByteBuffer.allocate(4).putInt(32 * 1024 + 1).array(),
                clarificationFrame("""[1,"prepare",${WORKBOOK_BODY_LIMIT + 1},"$PREVIEW_FIXTURE_PROFILE"]"""))) begin(port, bytes).expect(413)
            begin(port, clarificationFrame("""[1,"prepare",1,"$PREVIEW_FIXTURE_PROFILE"]""", byteArrayOf(1))).expect(415)
            begin(port, preview(supplier = "demo-electricidad")).expect(409)
            begin(port, preview(digest = "a".repeat(64))).expect(409)
            begin(port, preview(revision = supplierDraftRevision(sha256(supplierDraftWorkbook()), "B2"))).expect(409)
            assertContains(begin(port, preview()).expect(200), "\"decimal\":\"0\"")
        } finally { server.stop(0, 1_000) }
    }

    @Test fun `Netty preview shares existing auth and upload slots and observed close and deadline release them`() = runBlocking {
        val heldAuth = CountDownLatch(4)
        val releaseAuth = CountDownLatch(1)
        val heldUpload = CountDownLatch(4)
        val entered = CompletableDeferred<Unit>()
        val closed = CompletableDeferred<Unit>()
        val reads = AtomicInteger()
        val server = server {
            configurePlugins()
            receivePipeline.intercept(ApplicationReceivePipeline.Before) {
                reads.incrementAndGet()
                when (call.request.header(HttpHeaders.Authorization)) {
                    "Bearer hold-upload" -> { heldUpload.countDown(); awaitCancellation() }
                    "Bearer disconnect" -> try { entered.complete(Unit); awaitCancellation() } finally { closed.complete(Unit) }
                }
            }
            routing { route("/api/v1") {
                teachWorkbookMessageRoutes(timeoutMillis = 2_000, clarificationEnabled = true, supplierDraftEnabled = true,
                    draftPreviewEnabled = true) { token ->
                    if (token == "hold-auth") { heldAuth.countDown(); check(releaseAuth.await(7, TimeUnit.SECONDS)) }
                    Allowed
                }
            } }
        }
        try {
            server.start(wait = false)
            val port = server.resolvedConnectors().single().port
            suspend fun assertSlotReleased() = withTimeout(3_000) {
                while (true) {
                    val raw = withContext(Dispatchers.IO) { begin(port, prepare).use { it.getInputStream().readBytes().decodeToString() } }
                    if (raw.startsWith("HTTP/1.1 200 ")) {
                        assertContains(raw.substringBefore("\r\n\r\n").lowercase(), "cache-control: no-store")
                        break
                    }
                    assertTrue(raw.startsWith("HTTP/1.1 429 "), raw.substringBefore("\r\n"))
                    delay(5)
                }
            }
            val types = listOf(DRAFT_PREVIEW_MEDIA_TYPE, SUPPLIER_DRAFT_MEDIA_TYPE, CLARIFICATION_MEDIA_TYPE, DRAFT_PREVIEW_MEDIA_TYPE)
            val auth = types.map { type -> async(Dispatchers.IO) { begin(port, token = "hold-auth", type = type).expect(408) } }
            assertTrue(withContext(Dispatchers.IO) { heldAuth.await(2, TimeUnit.SECONDS) })
            begin(port).expect(429)
            auth.awaitAll()
            begin(port, type = SUPPLIER_DRAFT_MEDIA_TYPE).expect(429)
            assertEquals(0, reads.get())
            releaseAuth.countDown()
            // Worker release is asynchronous; use a successful ordinary attempt as a barrier without holding a slot.
            assertSlotReleased()
            begin(port, token = "disconnect").use { withTimeout(3_000) { entered.await() } }
            withTimeout(1_000) { closed.await() }
            val uploads = types.map { type -> async(Dispatchers.IO) { begin(port, token = "hold-upload", type = type).expect(408) } }
            assertTrue(withContext(Dispatchers.IO) { heldUpload.await(2, TimeUnit.SECONDS) })
            begin(port).expect(429)
            uploads.awaitAll()
            // Receiving the 408 does not prove that the server's cleanup has returned its permit.
            assertSlotReleased()
            assertEquals(7, reads.get())
        } finally { releaseAuth.countDown(); server.stop(0, 1_000) }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "BM_TEACH_CLARIFY_TEST_WEB", matches = ".+")
    fun `candidate Pages proxy and Ktor prepare and preview over loopback`() = runBlocking {
        val reads = AtomicInteger()
        val authCount = AtomicInteger()
        val offReads = AtomicInteger()
        val offAuth = AtomicInteger()
        val admins = InMemoryAdminUsersRepository().apply { add(admin) }
        val server = server {
            configurePlugins()
            receivePipeline.intercept(ApplicationReceivePipeline.Before) {
                // Four ordered requests finish before the fifth, for which the same admin is revoked.
                if (reads.incrementAndGet() == 4) admins.remove(admin)
            }
            mount(admins, authCount = authCount)
        }
        val off = server {
            configurePlugins()
            receivePipeline.intercept(ApplicationReceivePipeline.Before) { offReads.incrementAndGet() }
            mount(admins, enabled = false, authCount = offAuth)
        }
        try {
            server.start(wait = false)
            off.start(wait = false)
            runTeachPagesLoopback("chains draft preview Pages proxy and Ktor over loopback", mapOf(
                "BM_TEACH_PREVIEW_TEST_PORT" to server.resolvedConnectors().single().port.toString(),
                "BM_TEACH_PREVIEW_OFF_TEST_PORT" to off.resolvedConnectors().single().port.toString(),
                "BM_TEACH_PREVIEW_TEST_WORKBOOK" to Base64.getEncoder().encodeToString(workbook),
                "BM_TEACH_PREVIEW_TEST_ALTERNATE" to Base64.getEncoder().encodeToString(clarificationWorkbook()),
            ))
            assertEquals(5, authCount.get())
            assertEquals(4, reads.get(), "Revoked request must not open the body")
            assertEquals(0, offAuth.get())
            assertEquals(0, offReads.get())
        } finally { off.stop(0, 1_000); server.stop(0, 1_000) }
    }
}
