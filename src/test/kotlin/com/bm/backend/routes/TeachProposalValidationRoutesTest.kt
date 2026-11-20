package com.bm.backend.routes

import com.bm.backend.models.*
import com.bm.backend.repositories.ports.TeachProposalStoreUnavailable
import com.bm.backend.repositories.ports.TeachProposalImportStore
import com.bm.backend.services.*
import com.bm.backend.testing.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.net.InetSocketAddress
import java.net.Socket
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.sql.SQLException
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*

internal class TeachProposalValidationRoutesTest : TeachProposalHttpHarness() {
    private val path = "/api/v1$PROPOSAL_VALIDATION_PATH"
    private val absent = "00000000-0000-4000-8000-000000000001"
    private fun service(db: TeachActivationPostgres, fixture: TeachProposalPostgres, store: TeachProposalImportStore = fixture.store()) =
        assertNotNull(TeachProposalImportService.mount(assertNotNull(TeachSupplierSkillSelectionService.mount(db.reader(), registry)), store))

    private fun body(ref: String) = """{"validationRef":"$ref","confirmExpectedPrices":true}"""
    private fun confirm(port: Int, ref: String, token: String = "fixture-token", payload: String = body(ref),
                        query: String = "", type: String = "application/json", override: String? = null) = request(port, path + query, token)
        .header("Content-Type", type).apply { if (override != null) header("X-HTTP-Method-Override", override) }
        .POST(HttpRequest.BodyPublishers.ofString(payload)).build()

    private fun list(port: Int, done: TeachCallCompletion, query: String = "?validation=1", token: String = "fixture-token") =
        Json.decodeFromString<TeachValidatedImportPage>(send(get(port, query, token), 200, done))
    private fun result(raw: String) = Json.decodeFromString<TeachProposalValidationResult>(raw)
    private fun count(fixture: TeachProposalPostgres): Int {
        var count = -1
        fixture.owner { sql -> sql.createStatement().use { s -> s.executeQuery("SELECT count(*) FROM proposals.validation").use {
            check(it.next()); count = it.getInt(1)
        } } }
        return count
    }

    private fun sentinel(db: TeachActivationPostgres) = db.sentinel { sql ->
        val catalog = db.snapshot(sql)
        catalog to listOf("SELECT proposal_count FROM proposals.canary", "SELECT validation_count FROM proposals.validation").map { q ->
            sql.createStatement().use { s -> s.executeQuery(q).use { r -> check(r.next()); r.getInt(1) } }
        }
    }

    private fun audit(fixture: TeachProposalPostgres, batch: TeachValidatedImportBatch, result: TeachProposalValidationResult) {
        fixture.owner { sql -> sql.createStatement().use { s -> s.executeQuery("""SELECT b.environment, b.supplier, b.revision, b.generation,
            b.manifest, v.validation_ref, v.actor, v.result, v.validated_at, b.id FROM proposals.validation v
            JOIN proposals.batch b ON b.validation_ref = v.validation_ref AND b.actor = v.actor""").use { r ->
            assertTrue(r.next())
            assertEquals(listOf("qa", "gesternova", batch.revisionId, batch.generation, fixture.identity.manifest), (1..5).map(r::getString))
            assertTrue(r.getString(6) == batch.validationRef && r.getString(7) == "synthetic-author", "immutable fixture target/author")
            assertEquals("expected_prices_confirmed", r.getString(8))
            assertEquals(result.validatedAt, r.getTimestamp(9).toInstant().toString())
            assertEquals(batch.importId, r.getString(10))
            assertFalse(r.next())
        } } }
    }

    @Test fun `candidate Pages proxy and Ktor validate exact committed QA import over loopback`() {
        assumeTrue(System.getenv("BM_TEACH_CLARIFY_TEST_WEB") != null, "The required joint runs separately with Node 20")
        TeachActivationPostgres(registry.manifest, withProposals = true).use { db ->
            val fixture = checkNotNull(db.proposals)
            db.sentinel { db.seed(it, "f".repeat(64), 19) }
            val sentinelBefore = sentinel(db)
            val imports = service(db, fixture)
            val beforeWrite = AtomicReference<List<List<String>>>()
            val validation = assertNotNull(TeachProposalValidationService.mount(imports, fixture.validationStore(beforeInsert = {
                beforeWrite.compareAndSet(null, snapshot(fixture))
            })))
            lateinit var a: TeachValidatedImportBatch
            netty(imports, pilot(db), validation = validation) { port, done ->
                activate(port, done, books[0])
                val catalogBefore = db.qaOwner(db::snapshot)
                runTeachPagesLoopback("chains exact validation Pages proxy and Ktor over loopback", mapOf(
                    "BM_TEACH_VALIDATION_TEST_PORT" to port.toString(), "BM_TEACH_VALIDATION_PILOT" to "true", "BM_TEACH_IMPORT_PILOT" to "true",
                    "BM_TEACH_VALIDATION_TEST_REVISION" to books[0].revision,
                    "BM_TEACH_VALIDATION_TEST_BOOK" to Base64.getEncoder().encodeToString(workbook)), "proposal-validation")
                assertTrue(beforeWrite.get() == snapshot(fixture), "Validation must leave all proposal and batch rows unchanged")
                assertEquals(catalogBefore, db.qaOwner(db::snapshot))
                a = list(port, done).batches.single()
                assertEquals(importOracleRows(), a.proposals)
                val at = assertNotNull(a.validation).validatedAt
                audit(fixture, a, TeachProposalValidationResult("validated", a.importId, a.revisionId, a.generation, at))
                activate(port, done, books[1], books[0])
                val b = receipt(send(post(port, 2), 201, done))
                assertEquals(books[1].revision to "2", b.revisionId to b.generation)
                val both = list(port, done).batches
                assertNull(both[0].validation)
                assertTrue(both[1] == a, "A evidence must not transfer to B")
            }
            val remounted = service(db, fixture)
            netty(remounted, validation = assertNotNull(TeachProposalValidationService.mount(remounted, fixture.validationStore()))) { port, done ->
                val recovered = list(port, done).batches.last()
                assertTrue(a == recovered, "Committed database evidence survives recomposition")
                assertEquals(a.validation?.validatedAt, result(send(confirm(port, a.validationRef), 200, done)).validatedAt)
            }
            assertEquals(sentinelBefore, sentinel(db))
        }
    }

    @Test fun `guard precedes body and changed UID, nomination and fixture binding deny writes and replays`() {
        TeachActivationPostgres(registry.manifest, withProposals = true).use { db ->
            val fixture = checkNotNull(db.proposals)
            val imports = service(db, fixture)
            val validation = assertNotNull(TeachProposalValidationService.mount(imports, fixture.validationStore()))
            val revocation = AtomicBoolean()
            val mutate = AtomicReference("")
            val checks = AtomicInteger()
            val receives = AtomicInteger()
            netty(imports, pilot(db), validation = validation, revoked = revocation, receives = receives,
                onVerify = {
                    if (mutate.get() == "unavailable") error("synthetic identity unavailable")
                    if (mutate.get() == "revoke" && checks.incrementAndGet() == 2) revocation.set(true)
                },
                resolveUid = { uid -> when {
                    mutate.get() == "invalid uid" -> "ordinary-account"
                    mutate.get() == "switch" && checks.incrementAndGet() == 2 -> "synthetic-other"
                    else -> uid
                } }) { port, done ->
                assertEquals("", send(request(port, path, "fixture-token").GET().build(), 405, done))
                activate(port, done, books[0])
                assertEquals("", send(confirm(port, absent), 404, done))
                assertEquals(409, http.send(post(port, 98, incompatibleImportWorkbook()), HttpResponse.BodyHandlers.discarding()).statusCode()); done.await()
                assertTrue(list(port, done).batches.isEmpty())
                receipt(send(post(port, 1), 201, done))
                val target = list(port, done).batches.single()
                val before = receives.get()
                for ((token, error) in listOf("invalid" to 401, "not-admin" to 403, "not-nominated" to 403)) {
                    send(confirm(port, target.validationRef, token), error, done)
                    send(get(port, "?validation=1", token), error, done)
                }
                mutate.set("invalid uid")
                send(confirm(port, target.validationRef), 403, done)
                send(get(port, "?validation=1"), 403, done)
                mutate.set("unavailable")
                send(confirm(port, target.validationRef), 503, done)
                send(get(port, "?validation=1"), 503, done)
                mutate.set("")
                assertEquals(before, receives.get(), "Initial denial cannot open body")
                val foreign = send(confirm(port, target.validationRef, "other-token"), 404, done)
                assertEquals(foreign, send(confirm(port, absent), 404, done))
                val otherBatch = receipt(send(post(port, 2, token = "other-token"), 201, done))
                assertTrue(list(port, done, "?validation=1&cursor=${otherBatch.importId}", "other-token").batches.isEmpty())
                assertEquals(listOf(target.importId), list(port, done, "?validation=1&cursor=${otherBatch.importId}").batches.map { it.importId })
                assertNull(list(port, done).batches.single().validation)
                val malformed = listOf("{}", "[]", """{"validationRef":"${target.importId}","confirmExpectedPrices":true}""",
                    """{"validationRef":"${target.revisionId}","confirmExpectedPrices":true}""",
                    body(target.validationRef).replace("true", "false"), body(target.validationRef).replace("true", "\"true\""),
                    body(target.validationRef).dropLast(1) + ",\"confirmExpectedPrice\\u0073\":true}",
                    body(target.validationRef).dropLast(1) + ",\"actor\":\"synthetic-author\"}",
                    """{"receipt":"preview-only","confirmExpectedPrices":true}""")
                malformed.forEach { send(confirm(port, absent, payload = it), 400, done) }
                send(confirm(port, absent, query = "?x=1"), 400, done)
                send(confirm(port, absent, override = "GET"), 400, done)
                send(confirm(port, absent, type = "text/plain"), 415, done)
                send(confirm(port, absent, payload = " ".repeat(1025)), 413, done)
                send(get(port, "?validation=1&validation=1"), 400, done)
                send(get(port, "?cursor=3&validation=1"), 400, done)
                for (mode in listOf("switch", "revoke")) {
                    checks.set(0); mutate.set(mode)
                    send(confirm(port, target.validationRef), 403, done)
                    mutate.set(""); revocation.set(false)
                }
                assertEquals(0, count(fixture))
                result(send(confirm(port, target.validationRef), 201, done))
                checks.set(0); mutate.set("switch")
                send(confirm(port, target.validationRef), 403, done)
                checks.set(0); mutate.set("revoke")
                send(confirm(port, target.validationRef), 403, done)
                mutate.set("")
                send(get(port, "?validation=1"), 403, done)
                assertEquals(1, count(fixture))
            }
            assertNull(TeachProposalValidationService.mount(imports, fixture.validationStore(writeRole = "incorrect_role")))
            assertNull(TeachProposalValidationService.mount(imports, fixture.validationStore(database = db.qaDb)))
            assertNull(TeachProposalValidationService.mount(imports, fixture.validationStore(descriptor = fixture.identity.copy(storeId = "a".repeat(32)))))
            for ((env, provided, v) in listOf(Triple("qa", imports, null), Triple("qa", service(db, fixture), validation),
                Triple("prod", imports, validation))) {
                netty(provided, env = env, validation = v) { port, done ->
                    send(confirm(port, absent), 404, done)
                    send(get(port, "?validation=1"), 404, done)
                    if (env == "qa") page(send(get(port), 200, done))
                }
            }
        }
    }

    @Test fun `validation appends only A despite an overlapping activation and a retry observes the same immutable timestamp`() {
        TeachActivationPostgres(registry.manifest, withProposals = true).use { db ->
            val fixture = checkNotNull(db.proposals)
            val late = AtomicBoolean()
            val importHolding = CountDownLatch(1)
            val importRelease = CountDownLatch(1)
            val imports = service(db, fixture, fixture.store(afterRow = { row ->
                if (row == 16 && late.getAndSet(false)) { importHolding.countDown(); await(importRelease) }
            }))
            val inserted = CountDownLatch(1)
            val second = CountDownLatch(1)
            val release = CountDownLatch(1)
            val writers = AtomicInteger()
            val validation = assertNotNull(TeachProposalValidationService.mount(imports, fixture.validationStore(beforeInsert = {
                if (writers.incrementAndGet() == 2) second.countDown()
            }, beforeCommit = { inserted.countDown(); await(release) })))
            val sentinelBefore = sentinel(db)
            netty(imports, pilot(db), validation = validation) { port, done ->
                activate(port, done, books[0])
                send(post(port, 1), 201, done)
                val a = list(port, done).batches.single()
                val first = http.sendAsync(confirm(port, a.validationRef), HttpResponse.BodyHandlers.ofString())
                late.set(true)
                val lateA = http.sendAsync(post(port, 2), HttpResponse.BodyHandlers.ofString())
                val replay = try {
                    await(inserted)
                    await(importHolding)
                    assertEquals(0, count(fixture))
                    assertNull(list(port, done).batches.single().validation)
                    activate(port, done, books[1], books[0])
                    http.sendAsync(confirm(port, a.validationRef), HttpResponse.BodyHandlers.ofString()).also { await(second) }
                } finally { release.countDown(); importRelease.countDown() }
                val event = result(checked(first.get(18, TimeUnit.SECONDS), 201)); done.await()
                assertEquals(event.copy(kind = "alreadyValidated"), result(checked(replay.get(18, TimeUnit.SECONDS), 200))); done.await()
                val importedAfterB = receipt(checked(lateA.get(18, TimeUnit.SECONDS), 201)); done.await()
                assertEquals(books[0].revision to "1", event.revisionId to event.generation)
                audit(fixture, a, event)
                val before = db.qaOwner(db::snapshot)
                val historical = list(port, done).batches.first()
                assertEquals(importedAfterB.importId, historical.importId)
                assertNull(historical.validation)
                val historicalEvent = result(send(confirm(port, historical.validationRef), 201, done))
                assertEquals(books[0].revision to "1", historicalEvent.revisionId to historicalEvent.generation)
                send(post(port, 3), 201, done)
                val current = list(port, done).batches
                assertEquals(books[1].revision to "2", current[0].revisionId to current[0].generation)
                assertNull(current[0].validation)
                assertEquals(historicalEvent.validatedAt, current[1].validation?.validatedAt)
                assertEquals(event.validatedAt, current[2].validation?.validatedAt)
                assertEquals(before, db.qaOwner(db::snapshot))
            }
            assertEquals(sentinelBefore, sentinel(db))
        }
    }

    @Test fun `uncommitted and foreign fixture refs are absent and JDBC role privileges are least scope`() {
        val saved = TeachActivationPostgres(registry.manifest, withProposals = true).use { db ->
            val fixture = checkNotNull(db.proposals)
            val imports = service(db, fixture)
            val validation = assertNotNull(TeachProposalValidationService.mount(imports, fixture.validationStore()))
            acl(db, fixture)
            netty(imports, pilot(db), validation = validation) { port, done ->
                fixture.owner { sql ->
                    sql.autoCommit = false
                    val hidden = sql.prepareStatement("""INSERT INTO proposals.batch (actor, attempt, digest,
                        environment, supplier, revision, generation, manifest, source) VALUES
                        ('synthetic-author', ?, ?, 'qa', 'gesternova', ?, 1, ?, 'filename') RETURNING validation_ref""").use { s ->
                        s.setString(1, "f".repeat(32)); s.setString(2, "e".repeat(64)); s.setString(3, books[0].revision)
                        s.setString(4, fixture.identity.manifest); s.executeQuery().use { r -> check(r.next()); r.getString(1) }
                    }
                    try {
                        send(confirm(port, hidden), 404, done)
                        assertTrue(list(port, done).batches.isEmpty())
                    } finally { sql.rollback() }
                }
                activate(port, done, books[0])
                send(post(port, 1), 201, done)
                send(post(port, 2), 201, done)
                val (first, second) = list(port, done).batches
                fixture.validationConnection().use { sql -> sql.prepareStatement("""INSERT INTO proposals.validation (validation_ref, actor)
                    VALUES (?::uuid, 'synthetic-other')""").use { s ->
                    s.setString(1, second.validationRef)
                    assertEquals("23503", assertFailsWith<SQLException> { s.executeUpdate() }.sqlState)
                } }
                val event = result(send(confirm(port, first.validationRef), 201, done))
                val rows = list(port, done).batches
                assertEquals(event.validatedAt, rows[0].validation?.validatedAt)
                assertTrue(rows[1] == second && rows[1].validation == null, "A different load of the same revision must not inherit evidence")
                second.validationRef
            }
        }
        TeachActivationPostgres(registry.manifest, withProposals = true).use { db ->
            val fixture = checkNotNull(db.proposals)
            val imports = service(db, fixture)
            netty(imports, pilot(db), validation = assertNotNull(TeachProposalValidationService.mount(imports, fixture.validationStore()))) { port, done ->
                activate(port, done, books[0]); send(post(port, 1), 201, done); send(post(port, 2), 201, done)
                val visible = list(port, done).batches.first()
                assertEquals("2", visible.importId) // ID reused from the prior fixture's first committed batch.
                assertTrue(visible.validationRef != saved)
                assertEquals(send(confirm(port, absent), 404, done), send(confirm(port, saved), 404, done))
                assertEquals(0, count(fixture))
            }
        }
    }

    private fun acl(db: TeachActivationPostgres, fixture: TeachProposalPostgres) {
        fun denied(block: () -> Unit) = assertEquals("42501", assertFailsWith<SQLException>(block = block).sqlState)
        for (database in listOf(db.qaDb, db.sentinelDb, "postgres", "template1", "fixture_admin")) denied { fixture.validationConnection(database).close() }
        fixture.validationConnection().use { sql ->
            val forbidden = listOf("SELECT digest FROM proposals.batch", "SELECT attempt FROM proposals.batch", "SELECT supplier FROM proposals.batch",
                "SELECT * FROM proposals.proposal", "SELECT result FROM proposals.validation", "SELECT * FROM proposals.validation",
                "INSERT INTO proposals.batch (actor) VALUES ('synthetic-author')", "INSERT INTO proposals.validation (validated_at) VALUES (now())",
                "INSERT INTO proposals.validation (result) VALUES ('expected_prices_confirmed')", "DELETE FROM proposals.validation",
                "UPDATE proposals.validation SET validated_at = now()", "TRUNCATE proposals.validation", "ALTER TABLE proposals.validation ADD COLUMN no text",
                "CREATE TABLE proposals.no (c int)", "CREATE TABLE public.no (c int)", "CREATE SCHEMA no", "CREATE TEMP TABLE no (c int)",
                "SET ROLE fixture_owner", "UPDATE proposals.batch SET generation = 9", "DELETE FROM proposals.proposal", "UPDATE proposals.identity SET schema_version = 2")
            forbidden.forEach { query -> denied { sql.exec(query) } }
            sql.exec("SELECT validation_ref, actor, validated_at FROM proposals.validation")
        }
        for (writing in listOf(false, true)) fixture.connection(writing).use { sql ->
            denied { sql.exec("INSERT INTO proposals.validation (validation_ref, actor) VALUES (gen_random_uuid(), 'synthetic-author')") }
            denied { sql.exec("DELETE FROM proposals.validation") }
            if (writing) {
                denied { sql.exec("SELECT validation_ref FROM proposals.batch") }
                denied { sql.exec("INSERT INTO proposals.batch (validation_ref) VALUES (gen_random_uuid())") }
                denied { sql.exec("SELECT validation_ref FROM proposals.validation") }
            } else denied { sql.exec("SELECT result FROM proposals.validation") }
        }
        denied { db.qaConnection(fixture.db).close() }
        denied { db.readerConnection(fixture.db).close() }
        db.sentinel { sql -> sql.createStatement().use { s -> s.executeQuery("SELECT NOT has_database_privilege(current_user, '${fixture.db}', 'CONNECT')").use {
            assertTrue(it.next() && it.getBoolean(1))
        } } }
    }

    private fun wire(port: Int, value: String): Socket = Socket().apply {
        connect(InetSocketAddress("127.0.0.1", port), 2_000)
        soTimeout = 18_000
        getOutputStream().write(("POST $path HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\nAuthorization: Bearer fixture-token\r\n" +
            "Content-Type: application/json\r\nContent-Length: ${value.length}\r\n\r\n$value").encodeToByteArray())
        getOutputStream().flush()
    }

    @Test fun `rollback and response loss reconcile by scoped read and replay without a second event`() {
        TeachActivationPostgres(registry.manifest, withProposals = true).use { db ->
            val fixture = checkNotNull(db.proposals)
            val imports = service(db, fixture)
            val attempts = AtomicInteger()
            val fail = CountDownLatch(1)
            val failRelease = CountDownLatch(1)
            val enteredSecond = CountDownLatch(1)
            val committed = CountDownLatch(1)
            val release = CountDownLatch(1)
            val validation = assertNotNull(TeachProposalValidationService.mount(imports, fixture.validationStore(beforeInsert = {
                if (attempts.incrementAndGet() == 2) enteredSecond.countDown()
            }, beforeCommit = {
                if (attempts.get() == 1) { fail.countDown(); await(failRelease); throw TeachProposalStoreUnavailable() }
            }, afterCommit = { committed.countDown(); await(release) })))
            netty(imports, pilot(db), validation = validation) { port, done ->
                activate(port, done, books[0]); send(post(port, 1), 201, done)
                val a = list(port, done).batches.single()
                val proposals = snapshot(fixture)
                val first = http.sendAsync(confirm(port, a.validationRef), HttpResponse.BodyHandlers.ofString())
                try {
                    await(fail)
                    assertEquals(0, count(fixture))
                    assertNull(list(port, done).batches.single().validation)
                    wire(port, body(a.validationRef)).use {
                        await(enteredSecond)
                        failRelease.countDown()
                        checked(first.get(18, TimeUnit.SECONDS), 503); done.await()
                        await(committed)
                    } // The winning caller disconnects after commit but before the success can be sent.
                } finally { failRelease.countDown(); release.countDown() }
                done.await()
                val recovered = assertNotNull(list(port, done).batches.single().validation)
                val replay = result(send(confirm(port, a.validationRef), 200, done))
                assertEquals(recovered.validatedAt, replay.validatedAt)
                assertEquals(1, count(fixture))
                assertTrue(proposals == snapshot(fixture), "Validation cannot alter any committed proposal row")
            }
        }
    }

    @Test fun `shared admission rejects a fifth call and bounds both SQL lock wait and incomplete JSON`() {
        TeachActivationPostgres(registry.manifest, withProposals = true).use { db ->
            val fixture = checkNotNull(db.proposals)
            val imports = service(db, fixture)
            val blocked = CountDownLatch(1)
            val validation = assertNotNull(TeachProposalValidationService.mount(imports, fixture.validationStore(beforeInsert = { blocked.countDown() })))
            val willBlock = AtomicBoolean()
            val headers = CountDownLatch(3)
            netty(imports, pilot(db), validation = validation, onReceive = { if (willBlock.get()) headers.countDown() }) { port, done ->
                activate(port, done, books[0]); send(post(port, 1), 201, done)
                val batch = list(port, done).batches.single()
                val sockets = mutableListOf<Socket>()
                fixture.owner { sql ->
                    sql.autoCommit = false
                    sql.exec("LOCK TABLE proposals.validation IN SHARE MODE") // Compatible with reads, blocks only the append.
                    val locked = http.sendAsync(confirm(port, batch.validationRef), HttpResponse.BodyHandlers.ofString())
                    try {
                        await(blocked)
                        willBlock.set(true)
                        repeat(3) { sockets += Socket().apply {
                            connect(InetSocketAddress("127.0.0.1", port), 2_000); soTimeout = 18_000
                            getOutputStream().write(("POST $path HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\nAuthorization: Bearer fixture-token\r\n" +
                                "Content-Type: application/json\r\nContent-Length: 80\r\n\r\n{").encodeToByteArray())
                            getOutputStream().flush()
                        } }
                        await(headers)
                        send(get(port, "?validation=1"), 429, done)
                        sockets.drop(1).forEach { it.close(); done.await() }
                        checked(locked.get(18, TimeUnit.SECONDS), 408); done.await()
                        val status = sockets[0].getInputStream().bufferedReader().readLine()
                        assertTrue(status?.contains(" 408 ") == true, "Open JSON body must time out within the shared budget")
                        done.await()
                    } finally { sockets.forEach(Socket::close); sql.rollback() }
                }
                assertNull(list(port, done).batches.single().validation)
                assertEquals(0, count(fixture))
                assertEquals("validated", result(send(confirm(port, batch.validationRef), 201, done)).kind)
            }
        }
    }

    private fun await(latch: CountDownLatch) { assertTrue(latch.await(8, TimeUnit.SECONDS), "Fixture synchronization timed out") }
}
