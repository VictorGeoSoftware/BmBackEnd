package com.bm.backend.routes

import com.bm.backend.configurePlugins
import com.bm.backend.services.*
import com.bm.backend.testing.*
import com.google.firebase.ErrorCode
import com.google.firebase.auth.AuthErrorCode
import com.google.firebase.auth.FirebaseAuthException
import io.ktor.server.application.*
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.engine.applicationEngineEnvironment
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.request.ApplicationReceivePipeline
import io.ktor.server.routing.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.SecureRandom
import java.sql.SQLException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.*

class TeachSkillActivationRoutesTest {
    private val workbooks = activationWorkbooks()
    private val a = workbooks[0]
    private val b = workbooks[1]
    private val c = workbooks[2]
    private val registry = TeachActivationRegistry(workbooks.map { it.digest }.toSet())
    private val nominated = "author@example.invalid"
    private val previewPath = "/api/v1/teach-me/clarifications"
    private val activationPath = "/api/v1/teach-me/activation"
    private val time = AtomicLong(1_800_000_000L)
    private val clock = Clock.tick(object : Clock() {
        override fun instant() = Instant.ofEpochSecond(time.get())
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId) = this
    }, java.time.Duration.ofSeconds(1))
    private val http = HttpClient.newBuilder().proxy(java.net.ProxySelector.of(null)).build()
    private fun key() = ByteArray(32).also(SecureRandom()::nextBytes)

    private fun <T> netty(pilot: TeachSkillActivationService?, reads: AtomicInteger = AtomicInteger(),
                          admins: InMemoryAdminUsersRepository = InMemoryAdminUsersRepository().apply { add(nominated) },
                          environment: String = "qa", preview: Boolean = true, onVerify: (String) -> Unit = {},
                          completion: TeachCallCompletion? = null, run: (Int) -> T): T {
        val server = embeddedServer(Netty, applicationEngineEnvironment {
            config = MapApplicationConfig()
            connector { host = "127.0.0.1"; port = 0 }
            module {
                configurePlugins()
                completion?.install(this)
                receivePipeline.intercept(ApplicationReceivePipeline.Before) { reads.incrementAndGet() }
                routing { route("/api/v1") {
                    teachMeAccessRoutes(AdminAccessControlService(admins), TeachMeAccessPolicy(environment, nominated),
                        clarificationEnabled = true, supplierDraftEnabled = true, draftPreviewEnabled = preview,
                        clarificationAdapter = TeachClarificationAdapter { error("pilot reached clarification adapter") },
                        supplierDraftAdapter = TeachSupplierDraftAdapter { _, _ -> error("pilot reached draft adapter") },
                        activationPilot = pilot) { token ->
                        onVerify(token)
                        if (token !in setOf("fixture-token", "other", "recheck", "hold")) throw FirebaseAuthException(
                            ErrorCode.UNAUTHENTICATED, "invalid synthetic token", null, null, AuthErrorCode.INVALID_ID_TOKEN,
                        )
                        AuthenticatedFirebaseUser(if (token == "other") "other-actor" else "synthetic-author", nominated,
                            null, Instant.EPOCH, Instant.MAX)
                    }
                } }
            }
        })
        return try {
            server.start(wait = false)
            run(runBlocking { server.resolvedConnectors().single().port })
        } finally { server.stop(0, 1_000) }
    }

    private fun send(port: Int, bytes: ByteArray, status: Int, path: String, type: String, token: String = "fixture-token"): JsonObject? {
        val request = HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path"))
            .timeout(java.time.Duration.ofSeconds(18)).header("Authorization", "Bearer $token").header("Content-Type", type)
            .POST(HttpRequest.BodyPublishers.ofByteArray(bytes)).build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        assertEquals(status, response.statusCode())
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElse(null))
        return if (status == 200) Json.parseToJsonElement(response.body()).jsonObject else null
    }

    private fun preview(port: Int, workbook: TeachActivationWorkbook = a) = checkNotNull(send(port, workbook.preview(), 200, previewPath, QA_PREVIEW_MEDIA_TYPE))
    private fun json(preview: JsonObject, revision: String) = buildJsonObject {
        val previous = preview.getValue("active").jsonObject
        put("revisionId", revision)
        put("expectedRevisionId", previous.getValue("revisionId"))
        put("expectedGeneration", previous.getValue("generation"))
        put("receipt", preview.getValue("receipt"))
    }
    private fun activate(port: Int, body: JsonObject, status: Int, token: String = "fixture-token") =
        send(port, body.toString().encodeToByteArray(), status, activationPath, "application/json", token)

    private fun noBody(port: Int, path: String, type: String, status: Int, auth: String = "") {
        Socket().use { socket ->
            socket.soTimeout = 2_500
            socket.connect(InetSocketAddress("127.0.0.1", port), 2_000)
            socket.getOutputStream().write(("POST $path HTTP/1.1\r\nHost: 127.0.0.1\r\n${auth}Content-Type: $type\r\n" +
                "Content-Length: 1000\r\nConnection: close\r\n\r\n").encodeToByteArray())
            val response = socket.getInputStream().bufferedReader()
            assertTrue(response.readLine().startsWith("HTTP/1.1 $status "))
            assertTrue(generateSequence(response::readLine).takeWhile { it.isNotEmpty() }.any { it.equals("Cache-Control: no-store", ignoreCase = true) })
        }
    }

    private inner class OrderedCalls(private val port: Int, private val completion: TeachCallCompletion) {
        private var pending = false

        fun drain() {
            if (pending) completion.await()
            pending = false
        }

        // Wait at the next request, not before the caller's post-response snapshot or assertion.
        private fun <T> next(request: () -> T): T {
            drain()
            return request().also { pending = true }
        }

        fun send(bytes: ByteArray, status: Int, path: String, type: String) = next {
            this@TeachSkillActivationRoutesTest.send(port, bytes, status, path, type)
        }
        fun preview(workbook: TeachActivationWorkbook) = next { this@TeachSkillActivationRoutesTest.preview(port, workbook) }
        fun activate(body: JsonObject, status: Int, token: String = "fixture-token") = next {
            this@TeachSkillActivationRoutesTest.activate(port, body, status, token)
        }
        fun noBody(path: String, type: String, status: Int, auth: String = "") = next {
            this@TeachSkillActivationRoutesTest.noBody(port, path, type, status, auth)
        }
    }

    private fun orderedNetty(pilot: TeachSkillActivationService, reads: AtomicInteger = AtomicInteger(),
                             admins: InMemoryAdminUsersRepository = InMemoryAdminUsersRepository().apply { add(nominated) },
                             onVerify: (String) -> Unit = {}, run: OrderedCalls.() -> Unit) {
        val completion = TeachCallCompletion()
        netty(pilot, reads, admins, onVerify = onVerify, completion = completion) { port ->
            OrderedCalls(port, completion).apply(run).drain()
        }
    }

    private fun stagedActivation(port: Int, size: Int, token: String): Socket = Socket().apply {
        soTimeout = 17_000
        connect(InetSocketAddress("127.0.0.1", port), 2_000)
        getOutputStream().write(("POST $activationPath HTTP/1.1\r\nHost: 127.0.0.1\r\nAuthorization: Bearer $token\r\n" +
            "Content-Type: application/json\r\nContent-Length: $size\r\nConnection: close\r\n\r\n").encodeToByteArray())
    }

    private fun awaitRead(reads: AtomicInteger, count: Int) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4)
        while (reads.get() < count) {
            check(System.nanoTime() < deadline) { "Expected the receive pipeline to open" }
            Thread.sleep(5)
        }
    }

    @Test fun `default off with all prior opt-ins and no body access`() {
        val reads = AtomicInteger()
        netty(null, reads) { port ->
            noBody(port, activationPath, "application/json", 404)
            noBody(port, previewPath, QA_PREVIEW_MEDIA_TYPE, 404)
        }
        assertEquals(0, reads.get())
    }

    @Test fun `catalog isolation rollback authorization and restart use disposable PostgreSQL`() {
        TeachActivationPostgres(registry.manifest).use { db ->
            val sentinelPointer = "f".repeat(64)
            db.sentinel { sql ->
                db.seed(sql, sentinelPointer, 19)
                sql.exec("INSERT INTO pilot.audit VALUES ('sentinel-fixture', 'gesternova', '$sentinelPointer', NULL, 19, 'sentinel', 'prod', now(), 'activated')")
            }
            val sentinel = db.sentinel(db::snapshot)
            assertEquals("42501", assertFailsWith<SQLException> { db.qaConnection(db.sentinelDb).close() }.sqlState)
            assertEquals("42501", assertFailsWith<SQLException> { db.qaConnection("postgres").close() }.sqlState)
            db.qaConnection().use { sql ->
                for (statement in listOf("DELETE FROM pilot.active", "UPDATE pilot.audit SET actor = 'x'", "DELETE FROM pilot.audit",
                    "ALTER TABLE pilot.active ADD COLUMN forbidden text", "UPDATE pilot.identity SET manifest = 'x'", "CREATE TABLE public.forbidden (a int)")) {
                    assertEquals("42501", assertFailsWith<SQLException> { sql.exec(statement) }.sqlState)
                }
            }
            assertNull(TeachSkillActivationService.mount(db.store(database = db.sentinelDb), registry, clock, key()))
            assertNull(TeachSkillActivationService.mount(db.store(role = db.sentinelRole), registry, clock, key()))
            assertNull(TeachSkillActivationService.mount(db.store(id = "a".repeat(32)), registry, clock, key()))
            db.qaOwner { db.seed(it, sentinelPointer) }
            assertNull(TeachSkillActivationService.mount(db.store(), registry, clock, key()))
            db.qaOwner { it.exec("DELETE FROM pilot.active"); db.seed(it, a.revision) }
            val baseline = db.qaOwner(db::snapshot)
            val firstKey = key()
            val first = checkNotNull(TeachSkillActivationService.mount(db.store(), registry, clock, firstKey))
            netty(first, environment = "prod") { port ->
                noBody(port, activationPath, "application/json", 404)
                noBody(port, previewPath, QA_PREVIEW_MEDIA_TYPE, 404)
            }
            netty(first, preview = false) { port ->
                noBody(port, activationPath, "application/json", 404)
                noBody(port, previewPath, QA_PREVIEW_MEDIA_TYPE, 404)
            }
            val reads = AtomicInteger()
            val admins = InMemoryAdminUsersRepository().apply { add(nominated) }
            val checks = AtomicInteger()
            lateinit var old: JsonObject
            val originalTime = time.get()
            orderedNetty(first, reads, admins, onVerify = { token -> if (token == "recheck" && checks.incrementAndGet() == 2) admins.remove(nominated) }) {
                noBody(activationPath, "application/json", 401)
                noBody(activationPath, "application/json", 401, "Authorization: Bearer invalid\r\n")
                noBody(activationPath, "application/json", 401, "Authorization: Bearer fixture-token\r\nAuthorization: Bearer fixture-token\r\n")
                noBody(previewPath, QA_PREVIEW_MEDIA_TYPE, 401)
                assertEquals(0, reads.get())
                send(a.preview(), 200, previewPath, DRAFT_PREVIEW_MEDIA_TYPE)?.let {
                    assertEquals("preview", it.getValue("kind").jsonPrimitive.content)
                    assertFalse("receipt" in it)
                }
                val prepare = clarificationFrame("""[1,"prepare",${a.bytes.size},"$GESTERNOVA_PREVIEW_PROFILE"]""", a.bytes)
                send(prepare, 400, previewPath, QA_PREVIEW_MEDIA_TYPE)
                val proof = preview(b)
                val body = json(proof, b.revision)
                activate(JsonObject(body + ("revisionId" to JsonPrimitive(c.revision))), 409)
                activate(JsonObject(body + ("expectedGeneration" to JsonPrimitive("2"))), 409)
                val receipt = body.getValue("receipt").jsonPrimitive.content
                val codec = TeachActivationReceipts(firstKey, db.storeId, registry.manifest)
                val decoded = codec.verify(receipt)
                for (other in listOf(decoded.copy(profileKey = "other-profile"), decoded.copy(workbookSha256 = c.digest),
                    decoded.copy(supplierKey = "different-supplier"), decoded.copy(issuedAt = decoded.issuedAt + 1, expiresAt = decoded.expiresAt + 1))) {
                    activate(JsonObject(body + ("receipt" to JsonPrimitive(codec.issue(other)))), 409)
                }
                val alteration = receipt.substringBefore('.') + "." + receipt.substringAfter('.').let { (if (it[0] == 'A') 'B' else 'A') + it.drop(1) }
                activate(JsonObject(body + ("receipt" to JsonPrimitive(alteration))), 409)
                activate(body, 409, "other")
                val duplicate = body.toString().dropLast(1) + ",\"receip\\u0074\":\"different\"}"
                send(duplicate.encodeToByteArray(), 400, activationPath, "application/json")
                send(ByteArray(2049), 413, activationPath, "application/json")
                send(byteArrayOf(0xc3.toByte(), 0x28), 400, activationPath, "application/json")
                send(body.toString().encodeToByteArray(), 415, activationPath, "application/json; charset=utf-8")
                activate(body, 403, "recheck")
                noBody(activationPath, "application/json", 403, "Authorization: Bearer fixture-token\r\n")
                admins.add(nominated)
                assertEquals(baseline, db.qaOwner(db::snapshot))

                db.qaOwner { it.exec("UPDATE pilot.identity SET manifest = '${"0".repeat(64)}'") }
                activate(body, 503)
                send(b.preview(), 503, previewPath, QA_PREVIEW_MEDIA_TYPE)
                assertEquals(baseline, db.qaOwner(db::snapshot))
                db.qaOwner { it.exec("UPDATE pilot.identity SET manifest = '${registry.manifest}'") }

                db.qaOwner { it.exec("ALTER TABLE pilot.audit ADD CONSTRAINT fixture_append_fail CHECK (false) NOT VALID") }
                activate(body, 503)
                assertEquals(baseline, db.qaOwner(db::snapshot))
                db.qaOwner { it.exec("ALTER TABLE pilot.audit DROP CONSTRAINT fixture_append_fail") }
                db.qaOwner { it.exec("ALTER TABLE pilot.active ADD CONSTRAINT fixture_update_fail CHECK (generation < 2) NOT VALID") }
                activate(body, 503)
                assertEquals(baseline, db.qaOwner(db::snapshot))
                db.qaOwner { it.exec("ALTER TABLE pilot.active DROP CONSTRAINT fixture_update_fail") }
                time.set(originalTime + 300)
                activate(body, 409)
                time.set(originalTime + 299)
                assertEquals("activated", activate(body, 200)?.getValue("kind")?.jsonPrimitive?.content)
                activate(body, 409)
                old = json(preview(c), c.revision)
            }
            val committed = db.qaOwner(db::snapshot)
            assertEquals(listOf("gesternova", b.revision, "2"), committed[0].single())
            val event = committed[1].single()
            assertEquals(listOf("gesternova", b.revision, a.revision, "2", "synthetic-author", "qa"), event.subList(1, 7))
            assertEquals("activated", event.last())
            assertNull(TeachSkillActivationService.mount(db.store(), TeachActivationRegistry(setOf(a.digest, b.digest)), clock, key()))
            val equivalent = TeachActivationRegistry(setOf(c.digest, b.digest, a.digest))
            val next = checkNotNull(TeachSkillActivationService.mount(db.store(), equivalent, clock, key()))
            orderedNetty(next) {
                activate(old, 409)
                val now = preview(b)
                assertEquals(b.revision, now.getValue("active").jsonObject.getValue("revisionId").jsonPrimitive.content)
                val retry = json(now, b.revision)
                repeat(2) { assertEquals("alreadyActive", activate(retry, 200)?.getValue("kind")?.jsonPrimitive?.content) }
            }
            assertEquals(committed, db.qaOwner(db::snapshot))
            assertEquals(sentinel, db.sentinel(db::snapshot))
        }
    }

    @Test fun `concurrent first inserts and expiry after a real row lock leave one event`() {
        TeachActivationPostgres(registry.manifest).use { db ->
            val beforeLock = CountDownLatch(1)
            val go = CountDownLatch(1)
            val absence = CyclicBarrier(2) { assertEquals(listOf(emptyList(), emptyList()), db.qaOwner(db::snapshot)) }
            val store = db.store(beforeLock = { generation -> if (generation == "1") { beforeLock.countDown(); check(go.await(4, TimeUnit.SECONDS)) } },
                afterRead = { previous -> if (previous.generation == "0") absence.await(5, TimeUnit.SECONDS) })
            val pilot = checkNotNull(TeachSkillActivationService.mount(store, registry, clock, key()))
            netty(pilot) { port ->
                val offers = listOf(b, c).map { json(preview(port, it), it.revision) }
                val pool = Executors.newFixedThreadPool(2)
                try {
                    val results = pool.invokeAll(offers.map { offer -> Callable {
                        val request = HttpRequest.newBuilder(URI("http://127.0.0.1:$port$activationPath"))
                            .timeout(java.time.Duration.ofSeconds(18)).header("Authorization", "Bearer fixture-token").header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(offer.toString())).build()
                        http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode()
                    } }, 20, TimeUnit.SECONDS).map { it.get() }
                    assertEquals(listOf(200, 409), results.sorted())
                    val winner = if (results[0] == 200) b else c
                    val visible = db.qaOwner(db::snapshot)
                    assertEquals(listOf("gesternova", winner.revision, "1"), visible[0].single())
                    assertNull(visible[1].single()[3])
                    assertEquals(winner.revision, visible[1].single()[2])
                    val candidate = json(preview(port, a), a.revision)
                    db.qaOwner { locker ->
                        locker.autoCommit = false
                        locker.exec("SELECT * FROM pilot.active FOR UPDATE")
                        try {
                            val done = pool.submit { activate(port, candidate, 409) }
                            assertTrue(beforeLock.await(4, TimeUnit.SECONDS))
                            go.countDown()
                            // Do not advance the clock until PostgreSQL has the independent writer waiting on a lock.
                            val lockDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4)
                            while (true) {
                                val waiting = db.qaOwner { sql -> sql.createStatement().use { statement ->
                                    statement.executeQuery("SELECT count(*) FROM pg_stat_activity WHERE usename = '${db.qaRole}' AND wait_event_type = 'Lock'")
                                        .use { assertTrue(it.next()); it.getInt(1) }
                                } }
                                if (waiting == 1) break
                                check(System.nanoTime() < lockDeadline) { "Expected real database lock wait" }
                                Thread.sleep(10)
                            }
                            time.addAndGet(300)
                            assertEquals(visible, db.qaOwner(db::snapshot))
                            locker.rollback()
                            done.get(5, TimeUnit.SECONDS)
                        } finally { locker.rollback() }
                    }
                    assertEquals(visible, db.qaOwner(db::snapshot))
                } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(4, TimeUnit.SECONDS)) }
            }
        }
    }

    @Test fun `recheck has no extra auth queue and database lock uses the original request budget`() {
        TeachActivationPostgres(registry.manifest).use { db ->
            db.qaOwner { db.seed(it, a.revision) }
            val baseline = db.qaOwner(db::snapshot)
            val entered = CountDownLatch(4)
            val unblock = CountDownLatch(1)
            val drained = CountDownLatch(4)
            val reads = AtomicInteger()
            val pilot = checkNotNull(TeachSkillActivationService.mount(db.store(), registry, clock, key()))
            netty(pilot, reads, onVerify = { token -> if (token == "hold") {
                entered.countDown()
                try { check(unblock.await(8, TimeUnit.SECONDS)) } finally { drained.countDown() }
            } }) { port ->
                val body = json(preview(port, b), b.revision).toString().encodeToByteArray()
                stagedActivation(port, body.size, "fixture-token").use { staged ->
                    awaitRead(reads, 2)
                    val held = mutableListOf<Socket>()
                    try {
                        repeat(4) { held += stagedActivation(port, body.size, "hold") }
                        assertTrue(entered.await(4, TimeUnit.SECONDS))
                        staged.getOutputStream().write(body)
                        staged.soTimeout = 2_000
                        assertTrue(staged.getInputStream().bufferedReader().readLine().startsWith("HTTP/1.1 429 "))
                        assertEquals(baseline, db.qaOwner(db::snapshot))
                    } finally { held.forEach(Socket::close); unblock.countDown() }
                }
                assertTrue(drained.await(4, TimeUnit.SECONDS))
                // First guard + body wait + row lock must all fit one 15-second request budget.
                db.qaOwner { locker ->
                    locker.autoCommit = false
                    locker.exec("SELECT * FROM pilot.active FOR UPDATE")
                    try {
                        val readCount = reads.get()
                        val start = System.nanoTime()
                        stagedActivation(port, body.size, "fixture-token").use { staged ->
                            awaitRead(reads, readCount + 1)
                            Thread.sleep(3_000)
                            staged.getOutputStream().write(body)
                            assertTrue(staged.getInputStream().bufferedReader().readLine().startsWith("HTTP/1.1 408 "))
                            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) in 13_500..16_850)
                        }
                        assertEquals(baseline, db.qaOwner(db::snapshot))
                    } finally { locker.rollback() }
                }
                assertEquals(baseline, db.qaOwner(db::snapshot))
            }
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "BM_TEACH_CLARIFY_TEST_WEB", matches = ".+")
    fun `candidate Pages proxy and Ktor activate exact QA revision over loopback`() {
        TeachActivationPostgres(registry.manifest).use { db ->
            db.qaOwner { db.seed(it, a.revision) }
            db.sentinel { sql ->
                db.seed(sql, "f".repeat(64), 19)
                sql.exec("INSERT INTO pilot.audit VALUES ('sentinel-fixture', 'gesternova', '${"f".repeat(64)}', NULL, 19, 'sentinel', 'prod', now(), 'activated')")
            }
            val sentinel = db.sentinel(db::snapshot)
            assertEquals("42501", assertFailsWith<SQLException> { db.qaConnection(db.sentinelDb).close() }.sqlState)
            assertNull(TeachSkillActivationService.mount(db.store(database = db.sentinelDb), registry, clock, key()))
            val barrier = CyclicBarrier(2) {
                val visible = db.qaOwner(db::snapshot)
                assertEquals(listOf("gesternova", a.revision, "3"), visible[0].single())
                assertEquals(2, visible[1].size)
            }
            val store = db.store(beforeLock = { generation -> if (generation == "3") barrier.await(8, TimeUnit.SECONDS) })
            val pilot = checkNotNull(TeachSkillActivationService.mount(store, registry, clock, key()))
            netty(null) { offPort -> netty(pilot) { port ->
                val env = mapOf("BM_TEACH_ACTIVATION_TEST_PORT" to port.toString(), "BM_TEACH_ACTIVATION_OFF_TEST_PORT" to offPort.toString()) +
                    workbooks.mapIndexed { i, book -> "BM_TEACH_ACTIVATION_TEST_BOOK_$i" to Base64.getEncoder().encodeToString(book.bytes) }
                runTeachPagesLoopback("chains exact activation Pages proxy and Ktor over loopback", env, "activation")
            } }
            val result = db.qaOwner(db::snapshot)
            val final = result[0].single()[1]
            assertTrue(final in setOf(b.revision, c.revision))
            assertEquals(listOf("gesternova", final, "4"), result[0].single())
            val history = result[1]
            assertEquals(3, history.size)
            assertEquals(3, history.map { it.first() }.toSet().size)
            assertEquals(listOf(b.revision, a.revision, final), history.map { it[2] })
            assertEquals(listOf(a.revision, b.revision, a.revision), history.map { it[3] })
            assertEquals(listOf("2", "3", "4"), history.map { it[4] })
            assertTrue(history.all { it[5] == "synthetic-author" && it[6] == "qa" })
            assertEquals(sentinel, db.sentinel(db::snapshot))
        }
    }
}
