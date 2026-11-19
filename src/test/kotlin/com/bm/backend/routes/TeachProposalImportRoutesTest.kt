package com.bm.backend.routes

import com.bm.backend.configurePlugins
import com.bm.backend.firebase.FirebaseAdminFactory
import com.bm.backend.models.*
import com.bm.backend.repositories.ports.TeachActiveSkillReader
import com.bm.backend.repositories.ports.PriceTableRepositoryPort
import com.bm.backend.services.*
import com.bm.backend.testing.*
import com.google.firebase.ErrorCode
import com.google.firebase.auth.AuthErrorCode
import com.google.firebase.auth.FirebaseAuthException
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.engine.*
import io.ktor.server.netty.Netty
import io.ktor.server.request.*
import io.ktor.server.routing.*
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.mockkStatic
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.net.InetSocketAddress
import java.net.ProxySelector
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.SecureRandom
import java.sql.Connection
import java.sql.SQLException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class TeachProposalImportRoutesTest {
    private val books = activationWorkbooks().take(2)
    private val registry = TeachActivationRegistry(books.map { it.digest }.toSet())
    private val workbook = importWorkbook()
    private val path = "/api/v1$PROPOSAL_IMPORT_PATH"
    private val listPath = "/api/v1$PROPOSAL_IMPORT_LIST_PATH"
    private val nominated = "author@example.invalid"
    private val other = "another@example.invalid"
    private val excluded = "not-nominated@example.invalid"
    private val name = "bm-pilot-gesternova.xlsx"
    private val http = HttpClient.newBuilder().proxy(ProxySelector.of(null)).version(HttpClient.Version.HTTP_1_1).build()

    private class Counted(private val reader: TeachActiveSkillReader) : TeachActiveSkillReader by reader {
        val reads = AtomicInteger()
        override fun read(manifest: String, supplier: String, remainingMillis: () -> Long, checkpoint: () -> Unit): TeachActiveSkill? {
            reads.incrementAndGet()
            return reader.read(manifest, supplier, remainingMillis, checkpoint)
        }
    }

    private fun <T> netty(service: TeachProposalImportService?, pilot: TeachSkillActivationService? = null,
                          env: String = "qa", revoked: AtomicBoolean = AtomicBoolean(),
                          receives: AtomicInteger = AtomicInteger(), completion: TeachCallCompletion = TeachCallCompletion(),
                          otherRoutes: Route.(AdminAccessControlService) -> Unit = {},
                          run: (Int, TeachCallCompletion) -> T): T {
        val admins = InMemoryAdminUsersRepository().apply { add(nominated); add(other); add(excluded) }
        val server = embeddedServer(Netty, applicationEngineEnvironment {
            config = MapApplicationConfig()
            connector { host = "127.0.0.1"; port = 0 }
            module {
                configurePlugins()
                completion.install(this) { it.request.path() in setOf(path, listPath, "/api/v1/teach-me/activation", "/api/v1/teach-me/clarifications") }
                receivePipeline.intercept(ApplicationReceivePipeline.Before) { receives.incrementAndGet() }
                routing { route("/api/v1") {
                    val adminAccess = AdminAccessControlService(admins)
                    otherRoutes(adminAccess)
                    teachMeAccessRoutes(adminAccess, TeachMeAccessPolicy(env, "$nominated,$other"),
                        clarificationEnabled = pilot != null, supplierDraftEnabled = pilot != null, draftPreviewEnabled = pilot != null,
                        activationPilot = pilot, proposalImport = service) { token ->
                        val (uid, email) = when (token) {
                            "fixture-token" -> "synthetic-author" to (if (revoked.get()) excluded else nominated)
                            "other-token" -> "synthetic-other" to other
                            "not-admin" -> "synthetic-other" to "not-admin@example.invalid"
                            "not-nominated" -> "synthetic-other" to excluded
                            else -> throw FirebaseAuthException(ErrorCode.UNAUTHENTICATED, "invalid synthetic token", null, null,
                                AuthErrorCode.INVALID_ID_TOKEN)
                        }
                        AuthenticatedFirebaseUser(uid, email, null, Instant.EPOCH, Instant.MAX)
                    }
                } }
            }
        })
        return try {
            server.start(wait = false)
            run(runBlocking { server.resolvedConnectors().single().port }, completion)
        } finally { server.stop(100, 1_000) }
    }

    private fun pilot(db: TeachActivationPostgres) = assertNotNull(TeachSkillActivationService.mount(db.store(), registry,
        Clock.systemUTC(), ByteArray(32).also(SecureRandom()::nextBytes)))
    private fun uri(port: Int, path: String) = URI("http://127.0.0.1:$port$path")
    private fun request(port: Int, path: String, token: String) = HttpRequest.newBuilder(uri(port, path)).timeout(Duration.ofSeconds(18))
        .header("Authorization", "Bearer $token")
    private fun id(number: Int) = number.toString(16).padStart(32, '0')
    private fun encoded(filename: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(filename.encodeToByteArray())
    private fun post(port: Int, attempt: Int, bytes: ByteArray = workbook, filename: String = name, token: String = "fixture-token") =
        request(port, path, token).header("Content-Type", WORKBOOK_MEDIA_TYPE).header(WORKBOOK_FILENAME_HEADER, encoded(filename))
            .header(PROPOSAL_IMPORT_ATTEMPT_HEADER, id(attempt)).POST(HttpRequest.BodyPublishers.ofByteArray(bytes)).build()
    private fun get(port: Int, query: String = "", token: String = "fixture-token") = request(port, listPath + query, token).GET().build()
    private fun checked(response: HttpResponse<String>, status: Int): String {
        assertEquals(status, response.statusCode(), "unexpected import HTTP status")
        assertEquals(listOf("no-store"), response.headers().allValues("Cache-Control"))
        return response.body()
    }
    private fun send(req: HttpRequest, status: Int, completion: TeachCallCompletion): String =
        checked(http.send(req, HttpResponse.BodyHandlers.ofString()), status).also { completion.await() }
    private fun receipt(raw: String) = Json.decodeFromString<TeachProposalImportReceipt>(raw)
    private fun page(raw: String) = Json.decodeFromString<TeachProposalImportPage>(raw)
    private fun assertError(raw: String, error: String) = assertEquals(buildJsonObject { put("error", error) }, Json.parseToJsonElement(raw))

    private fun activate(port: Int, completion: TeachCallCompletion, book: TeachActivationWorkbook,
                         previous: TeachActivationWorkbook? = null) {
        val priorGeneration = if (previous == null) "0" else "1"
        val preview = Json.parseToJsonElement(send(request(port, "/api/v1/teach-me/clarifications", "fixture-token")
            .header("Content-Type", QA_PREVIEW_MEDIA_TYPE).POST(HttpRequest.BodyPublishers.ofByteArray(book.preview())).build(), 200, completion)).jsonObject
        assertEquals(book.revision, preview.getValue("preview").jsonObject.getValue("revisionId").jsonPrimitive.content)
        val active = preview.getValue("active").jsonObject
        assertEquals(previous?.revision, active.getValue("revisionId").jsonPrimitive.contentOrNull)
        assertEquals(priorGeneration, active.getValue("generation").jsonPrimitive.content)
        val proof = buildJsonObject {
            put("revisionId", book.revision); put("expectedRevisionId", active.getValue("revisionId")); put("expectedGeneration", priorGeneration)
            put("receipt", preview.getValue("receipt"))
        }
        assertEquals("activated", Json.parseToJsonElement(send(request(port, "/api/v1/teach-me/activation", "fixture-token")
            .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(proof.toString())).build(),
            200, completion)).jsonObject.getValue("kind").jsonPrimitive.content)
    }

    private fun snapshot(fixture: TeachProposalPostgres) = buildList {
        fixture.owner { sql ->
            sql.transactionIsolation = Connection.TRANSACTION_REPEATABLE_READ
            sql.autoCommit = false
            listOf("SELECT row_to_json(b)::text FROM proposals.batch b ORDER BY id",
                "SELECT row_to_json(p)::text FROM proposals.proposal p ORDER BY batch_id, workbook_row").forEach { q ->
                add(sql.createStatement().use { s -> s.executeQuery(q).use { r -> buildList { while (r.next()) add(r.getString(1)) } } })
            }
        }
    }

    private fun acl(db: TeachActivationPostgres, fixture: TeachProposalPostgres) {
        fun denied(block: () -> Unit) = assertEquals("42501", assertFailsWith<SQLException>(block = block).sqlState)
        for (writer in listOf(false, true)) {
            for (foreign in listOf(db.qaDb, db.sentinelDb, "postgres")) denied { fixture.connection(writer, foreign).close() }
            fixture.connection(writer).use { sql ->
                for (statement in listOf("CREATE TABLE proposals.no (c int)", "CREATE SCHEMA no", "CREATE TEMP TABLE no (c int)",
                    "DELETE FROM proposals.batch", "UPDATE proposals.batch SET generation = 9", "TRUNCATE proposals.proposal",
                    "UPDATE proposals.identity SET schema_version = 2", "ALTER TABLE proposals.batch ADD COLUMN no text")) denied { sql.exec(statement) }
                if (!writer) for (statement in listOf("SELECT digest FROM proposals.batch", "SELECT attempt FROM proposals.batch",
                    "INSERT INTO proposals.batch DEFAULT VALUES", "INSERT INTO proposals.proposal DEFAULT VALUES")) denied { sql.exec(statement) }
                else denied { sql.exec("SELECT * FROM proposals.proposal") }
            }
        }
        denied { db.qaConnection(fixture.db).close() }
        denied { db.readerConnection(fixture.db).close() }
        db.sentinel { sql ->
            assertTrue(sql.createStatement().use { s -> s.executeQuery("SELECT NOT has_database_privilege(current_user, '${fixture.db}', 'CONNECT')").use { r -> r.next() && r.getBoolean(1) } })
        }
    }

    private fun sentinel(db: TeachActivationPostgres) = db.sentinel { sql ->
        val snapshot = db.snapshot(sql)
        val canary = sql.createStatement().use { s -> s.executeQuery("SELECT proposal_count FROM proposals.canary").use { r -> check(r.next()); r.getInt(1) } }
        snapshot to canary
    }

    @Test fun `candidate Pages proxy and Ktor import and list exact QA Excel proposals over loopback`() {
        assumeTrue(System.getenv("BM_TEACH_CLARIFY_TEST_WEB") != null, "The required joint runs separately with Node 20")
        TeachActivationPostgres(registry.manifest, withProposals = true).use { db ->
            val fixture = checkNotNull(db.proposals)
            acl(db, fixture)
            db.sentinel { db.seed(it, "f".repeat(64), 19) }
            val sentinelBefore = sentinel(db)
            val selector = assertNotNull(TeachSupplierSkillSelectionService.mount(db.reader(), registry))
            netty(assertNotNull(TeachProposalImportService.mount(selector, fixture.store())), pilot(db)) { port, completion ->
                activate(port, completion, books[0])
                val catalogBefore = db.qaOwner(db::snapshot)
                val data = mapOf("BM_TEACH_IMPORT_TEST_PORT" to port.toString(), "BM_TEACH_IMPORT_PILOT" to "true",
                    "BM_TEACH_IMPORT_TEST_REVISION" to books[0].revision,
                    "BM_TEACH_IMPORT_TEST_BOOK" to Base64.getEncoder().encodeToString(workbook),
                    "BM_TEACH_IMPORT_TEST_MARKED" to Base64.getEncoder().encodeToString(markedImportWorkbook()),
                    "BM_TEACH_IMPORT_TEST_INCOMPATIBLE" to Base64.getEncoder().encodeToString(incompatibleImportWorkbook()))
                runTeachPagesLoopback("chains exact import Pages proxy and Ktor over loopback", data, "proposal-import")
                assertEquals(catalogBefore, db.qaOwner(db::snapshot))
                assertEquals(listOf(2, 6), snapshot(fixture).map { it.size })
            }
            netty(assertNotNull(TeachProposalImportService.mount(assertNotNull(TeachSupplierSkillSelectionService.mount(db.reader(), registry)), fixture.store()))) { port, done ->
                val list = page(send(get(port), 200, done))
                assertEquals(List(2) { importOracleRows() }, list.batches.map { it.proposals })
                assertEquals(List(2) { books[0].revision }, list.batches.map { it.revisionId })
                assertEquals(listOf("content", "filename"), buildList {
                    fixture.owner { sql -> sql.createStatement().use { s -> s.executeQuery("SELECT source FROM proposals.batch ORDER BY id DESC").use { r ->
                        while (r.next()) add(r.getString(1))
                    } } }
                })
            }
            assertEquals(sentinelBefore, sentinel(db))
        }
    }

    @Test fun `real A snapshot stays A after B commits with actor scoped pages and append only replay`() {
        TeachActivationPostgres(registry.manifest, withProposals = true).use { db ->
            val fixture = checkNotNull(db.proposals)
            val reader = Counted(db.reader())
            val selector = assertNotNull(TeachSupplierSkillSelectionService.mount(reader, registry))
            val selected = CountDownLatch(1)
            val continueA = CountDownLatch(1)
            val observed = mutableListOf<TeachRegisteredSkillRevision>()
            val service = assertNotNull(TeachProposalImportService.mount(selector, fixture.store()) { revision, shape, check ->
                synchronized(observed) { observed.add(revision) }
                if (revision.revisionId == books[0].revision) { selected.countDown(); await(continueA) }
                applyTeachPriceRule(revision.rule, shape, check)
            })
            netty(service, pilot(db)) { port, done ->
                activate(port, done, books[0])
                val underway = http.sendAsync(post(port, 1), HttpResponse.BodyHandlers.ofString())
                try { await(selected); activate(port, done, books[1], books[0]); assertTrue(page(send(get(port), 200, done)).batches.isEmpty()) }
                finally { continueA.countDown() }
                val a = receipt(checked(underway.get(18, TimeUnit.SECONDS), 201)); done.await()
                assertEquals(books[0].revision to "1", a.revisionId to a.generation)
                val b = receipt(send(post(port, 2, markedImportWorkbook(), "carga.xlsx"), 201, done))
                assertEquals(books[1].revision to "2", b.revisionId to b.generation)
                assertEquals(books.map { registry.exact(GESTERNOVA_SUPPLIER, it.revision) }, synchronized(observed) { observed.toList() })
                assertEquals(2, reader.reads.get())
                val beforeReplay = snapshot(fixture)
                assertEquals(a.copy(kind = "alreadyStored"), receipt(send(post(port, 1), 200, done)))
                assertError(send(post(port, 1, filename = "bm-pilot-gesternova-20td.xlsx"), 409, done), "attempt_mismatch")
                assertError(send(post(port, 1, incompatibleImportWorkbook()), 409, done), "attempt_mismatch")
                assertTrue(beforeReplay == snapshot(fixture), "Replay/mismatch must leave the entire proposal store unchanged")
                assertEquals(2, reader.reads.get())
                val another = receipt(send(post(port, 1, token = "other-token"), 201, done))
                val own = listOf(a, b) + (3..6).map { receipt(send(post(port, it), 201, done)) }
                val first = page(send(get(port), 200, done)); val second = page(send(get(port, "?cursor=${first.nextCursor}"), 200, done))
                assertEquals(4, first.batches.size); assertNull(second.nextCursor)
                assertEquals(own.reversed().map { it.importId }, (first.batches + second.batches).map { it.importId })
                assertTrue((first.batches + second.batches).all { it.proposals == importOracleRows() })
                assertEquals(listOf(another.importId), page(send(get(port, "?cursor=${first.nextCursor}", "other-token"), 200, done)).batches.map { it.importId })
                assertEquals(listOf(7, 21), snapshot(fixture).map { it.size })
                println("Synthetic snapshot race: ${a.revisionId}/${a.generation} committed after ${b.revisionId}/${b.generation} activated; 7 batches / 21 proposals, actor scopes 6 + 1")
            }
        }
    }

    @Test fun `incompatible and SQL rollback never expose partial writes and revocation outranks record`() {
        TeachActivationPostgres(registry.manifest, withProposals = true).use { db ->
            val fixture = checkNotNull(db.proposals)
            val reader = Counted(db.reader())
            val selector = assertNotNull(TeachSupplierSkillSelectionService.mount(reader, registry))
            val revoked = AtomicBoolean()
            val revokeBeforeRecord = AtomicBoolean()
            val failRow = AtomicBoolean()
            val rowWritten = CountDownLatch(1)
            val finishFailure = CountDownLatch(1)
            val receives = AtomicInteger()
            val service = assertNotNull(TeachProposalImportService.mount(selector, fixture.store(afterRow = { row ->
                if (row == 16 && failRow.get()) { rowWritten.countDown(); await(finishFailure); throw SQLException("synthetic rollback") }
            })) { revision, shape, check ->
                applyTeachPriceRule(revision.rule, shape, check).also { if (revokeBeforeRecord.get()) revoked.set(true) }
            })
            netty(service, pilot(db), revoked = revoked, receives = receives) { port, done ->
                assertError(send(post(port, 1), 409, done), "no_active_skill")
                activate(port, done, books[0]); send(post(port, 1), 201, done)
                val baseline = snapshot(fixture); val catalog = db.qaOwner(db::snapshot)
                val count = reader.reads.get()
                assertError(send(post(port, 2, markedImportWorkbook(markerRow(1, "gesternova", "hidden=\"1\""))), 409, done), "invalid_supplier_evidence")
                assertEquals(count, reader.reads.get())
                assertError(send(post(port, 2, incompatibleImportWorkbook()), 409, done), "incompatible_workbook")
                assertTrue(baseline == snapshot(fixture), "An incompatible import changed the proposal store")
                val viewsBefore = reader.reads.get()
                val workbookViews = listOf<(String) -> String>(
                    { it.replace("</bookViews>", "</bookViews><bookViews><workbookView/></bookViews>") },
                    { it.replace("""visibility="visible"""", """xmlns:fixture="urn:synthetic:workbook-view" fixture:visibility="hidden"""") },
                    { it.replace("<workbookView ", """<workbookView xmlns="urn:synthetic:workbook-view" """) },
                    { it.replace("<bookViews>", """<bookViews xmlns="urn:synthetic:workbook-view">""") },
                    { it.replace("""visibility="visible"""", """visibility="hidden"""") },
                )
                workbookViews.forEachIndexed { index, editView ->
                    // This is the ordinary filename-only fixture; no BM Identity can reject it during selection.
                    val variant = syntheticZip(importParts().apply { edit("xl/workbook.xml", editView) })
                    assertError(send(post(port, 20 + index, variant), 409, done), "incompatible_workbook")
                    assertTrue(baseline == snapshot(fixture), "Incompatible workbook views changed the proposal store")
                }
                assertEquals(viewsBefore + workbookViews.size, reader.reads.get())
                val readCount = receives.get()
                for ((token, error) in listOf("invalid" to 401, "not-admin" to 403, "not-nominated" to 403)) {
                    stage(port, token, 3).use { peer -> assertTrue(peer.getInputStream().bufferedReader().readLine().contains(" $error ")) }
                    done.await()
                    send(get(port, token = token), error, done)
                }
                assertEquals(readCount, receives.get())
                send(request(port, path, "fixture-token").GET().build(), 405, done)
                send(request(port, listPath, "fixture-token").POST(HttpRequest.BodyPublishers.noBody()).build(), 405, done)
                for (query in listOf("?cursor=0", "?cursor=01", "?cursor=9223372036854775808", "?cursor=1&cursor=1", "?actor=test")) send(get(port, query), 400, done)
                stage(port, "fixture-token", 3, 5 * 1024 * 1024 + 1).use { peer ->
                    assertTrue(peer.getInputStream().bufferedReader().readLine().contains(" 413 "))
                }
                done.await(); assertEquals(readCount, receives.get())
                revokeBeforeRecord.set(true)
                send(post(port, 3), 403, done); send(get(port), 403, done)
                assertTrue(baseline == snapshot(fixture), "A revoked attempt changed the proposal store")
                revoked.set(false); revokeBeforeRecord.set(false); failRow.set(true)
                val failure = http.sendAsync(post(port, 4), HttpResponse.BodyHandlers.ofString())
                try {
                    await(rowWritten)
                    assertEquals(1, page(send(get(port), 200, done)).batches.size)
                    assertTrue(baseline == snapshot(fixture), "Uncommitted work was visible to a private fixture reader")
                } finally { finishFailure.countDown() }
                assertError(checked(failure.get(18, TimeUnit.SECONDS), 503), "store_unavailable"); done.await()
                assertTrue(baseline == snapshot(fixture), "Rollback changed existing proposals")
                assertTrue(catalog == db.qaOwner(db::snapshot), "Import changed the QA catalog")
                failRow.set(false); send(post(port, 4), 201, done)
                assertNull(TeachProposalImportService.mount(selector, fixture.store(database = db.qaDb)))
                assertNull(TeachProposalImportService.mount(selector, fixture.store(writeRole = db.qaRole)))
                fixture.owner { it.exec("UPDATE proposals.identity SET schema_version = 2") }
                assertError(send(get(port), 503, done), "store_unavailable")
                assertError(send(post(port, 5), 503, done), "store_unavailable")
            }
            netty(null) { port, done -> send(get(port), 404, done); send(post(port, 9), 404, done) }
            netty(service, env = "prod") { port, done -> send(get(port), 404, done); send(post(port, 9), 404, done) }
        }
    }

    @Test fun `concurrent claims dedupe and a lost committed response recovers without pointer lookup`() {
        TeachActivationPostgres(registry.manifest, withProposals = true).use { db ->
            val fixture = checkNotNull(db.proposals)
            val reader = Counted(db.reader())
            val selector = assertNotNull(TeachSupplierSkillSelectionService.mount(reader, registry))
            val firstRow = CountDownLatch(1); val secondClaim = CountDownLatch(2); val finishFirst = CountDownLatch(1)
            val committed = CountDownLatch(1); val allowResponse = CountDownLatch(1)
            val loss = AtomicBoolean(); val claims = AtomicInteger()
            val store = fixture.store(afterRow = { if (it == 16 && claims.get() <= 2 && !loss.get()) {
                firstRow.countDown(); await(finishFirst)
            } }, beforeClaim = { claims.incrementAndGet(); secondClaim.countDown() }, afterCommit = {
                if (loss.get()) { committed.countDown(); await(allowResponse) }
            })
            netty(assertNotNull(TeachProposalImportService.mount(selector, store)), pilot(db)) { port, done ->
                activate(port, done, books[0])
                val first = http.sendAsync(post(port, 10), HttpResponse.BodyHandlers.ofString())
                try {
                    await(firstRow)
                    val second = http.sendAsync(post(port, 10), HttpResponse.BodyHandlers.ofString())
                    await(secondClaim)
                    assertTrue(page(send(get(port), 200, done)).batches.isEmpty())
                    assertFalse(second.isDone)
                    finishFirst.countDown()
                    val success = receipt(checked(first.get(18, TimeUnit.SECONDS), 201)); done.await()
                    assertEquals(success.copy(kind = "alreadyStored"), receipt(checked(second.get(18, TimeUnit.SECONDS), 200))); done.await()
                } finally { finishFirst.countDown() }
                assertEquals(listOf(1, 3), snapshot(fixture).map { it.size })
                loss.set(true)
                try {
                    stage(port, "fixture-token", 11).use { peer -> peer.getOutputStream().write(workbook); await(committed) }
                    val count = reader.reads.get()
                    allowResponse.countDown(); done.await(); loss.set(false)
                    val recovered = receipt(send(post(port, 11), 200, done))
                    assertEquals(books[0].revision, recovered.revisionId)
                    assertEquals(count, reader.reads.get())
                    assertTrue(page(send(get(port), 200, done)).batches.any { it.importId == recovered.importId })
                    assertEquals(listOf(2, 6), snapshot(fixture).map { it.size })
                } finally { allowResponse.countDown() }
            }
        }
    }

    @Test fun `four shared slots reject excess GET and release after JDBC ends`() {
        TeachActivationPostgres(registry.manifest, withProposals = true).use { db ->
            val fixture = checkNotNull(db.proposals)
            val selector = assertNotNull(TeachSupplierSkillSelectionService.mount(db.reader(), registry))
            val all = CountDownLatch(4); val release = CountDownLatch(1)
            val service = assertNotNull(TeachProposalImportService.mount(selector, fixture.store(beforeClaim = { all.countDown(); await(release) })))
            netty(service, pilot(db)) { port, done ->
                activate(port, done, books[0])
                val calls = (1..4).map { http.sendAsync(post(port, it), HttpResponse.BodyHandlers.ofString()) }
                try { await(all); send(get(port), 429, done) } finally { release.countDown() }
                calls.forEach { checked(it.get(18, TimeUnit.SECONDS), 201); done.await() }
                assertEquals(4, page(send(get(port), 200, done)).batches.size)
            }
        }
    }

    @Test fun `the shared deadline stops SQL lock wait before committing and releases admission`() {
        TeachActivationPostgres(registry.manifest, withProposals = true).use { db ->
            val fixture = checkNotNull(db.proposals)
            val selector = assertNotNull(TeachSupplierSkillSelectionService.mount(db.reader(), registry))
            val claiming = CountDownLatch(1)
            val store = fixture.store(beforeClaim = { claiming.countDown() })
            netty(assertNotNull(TeachProposalImportService.mount(selector, store)), pilot(db)) { port, done ->
                activate(port, done, books[0])
                fixture.owner { lock ->
                    lock.autoCommit = false
                    lock.exec("LOCK TABLE proposals.proposal IN SHARE MODE")
                    try {
                        val expired = http.sendAsync(post(port, 1), HttpResponse.BodyHandlers.ofString())
                        await(claiming)
                        checked(expired.get(18, TimeUnit.SECONDS), 408); done.await()
                        assertTrue(page(send(get(port), 200, done)).batches.isEmpty())
                    } finally { lock.rollback() }
                }
                assertEquals(listOf(0, 0), snapshot(fixture).map { it.size })
                send(post(port, 1), 201, done)
            }
        }
    }

    private fun stage(port: Int, token: String, attempt: Int, length: Int = workbook.size): Socket = Socket().apply {
        try {
            soTimeout = 3_000
            connect(InetSocketAddress("127.0.0.1", port), 2_000)
            getOutputStream().write(("POST $path HTTP/1.1\r\nHost: 127.0.0.1\r\nAuthorization: Bearer $token\r\n" +
                "Content-Length: $length\r\nContent-Type: $WORKBOOK_MEDIA_TYPE\r\n$WORKBOOK_FILENAME_HEADER: ${encoded(name)}\r\n" +
                "$PROPOSAL_IMPORT_ATTEMPT_HEADER: ${id(attempt)}\r\nConnection: close\r\n\r\n").encodeToByteArray())
        } catch (failure: Throwable) { close(); throw failure }
    }

    private fun await(latch: CountDownLatch) = check(latch.await(8, TimeUnit.SECONDS)) { "Synthetic import test barrier did not resolve" }

    internal fun isolatedPdfChild() {
        check(Regex("http://127\\.0\\.0\\.1:[1-9][0-9]{0,4}").matches(checkNotNull(System.getenv("DOCLING_PRICE_TABLES_API_URL"))))
        mockkObject(FirebaseAdminFactory)
        every { FirebaseAdminFactory.init() } answers { error("Firebase bootstrap forbidden in this fixture") }
        mockkStatic(::verifyFirebaseIdToken)
        every { verifyFirebaseIdToken("fixture-token") } returns AuthenticatedFirebaseUser("synthetic-author", nominated, null, Instant.EPOCH, Instant.MAX)

        val repo = SyntheticPdfPriceTableRepository()
        val notifications = mutableListOf<PriceUpdatesNotification>()
        val external = ExternalApiService()
        val notify = object : PriceUpdatesNotifier {
            override suspend fun notify(notification: PriceUpdatesNotification) { synchronized(notifications) { notifications.add(notification) } }
        }
        TeachActivationPostgres(registry.manifest, withProposals = true).use { db ->
            val fixture = checkNotNull(db.proposals)
            val selector = assertNotNull(TeachSupplierSkillSelectionService.mount(db.reader(), registry))
            netty(assertNotNull(TeachProposalImportService.mount(selector, fixture.store())), pilot(db), otherRoutes = { admin ->
                priceTableRoutes(PriceTableService(repo), external, notify, AccessControlService(InMemoryGrantedUsersRepository()), admin,
                    InternalApiAuthService(null))
            }) { port, done ->
                val upload = "/api/v1/upload-price-proposal"
                val listPdf = "/api/v1/admin/price-table-results"
                fun pdf(request: HttpRequest, status: Int) = http.send(request, HttpResponse.BodyHandlers.ofString()).let {
                    assertEquals(status, it.statusCode()); it.body()
                }
                fun legacy() = Json.decodeFromString<PriceTableResponse>(pdf(request(port, listPdf, "fixture-token").GET().build(), 200))
                fun uploadPdf() = request(port, upload, "fixture-token").header("Content-Type", "multipart/form-data; boundary=SyntheticPdfBoundary")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(("--SyntheticPdfBoundary\r\nContent-Disposition: form-data; " +
                        "name=\"file\"; filename=\"$SYNTHETIC_PDF_NAME\"\r\nContent-Type: application/pdf\r\n\r\n").encodeToByteArray() +
                        syntheticPdfBytes() + "\r\n--SyntheticPdfBoundary--\r\n".encodeToByteArray())).build()
                assertTrue(legacy().results.isEmpty())
                val emptyExcel = snapshot(fixture)
                val created = Json.decodeFromString<UploadPriceProposalResponse>(pdf(uploadPdf(), 201))
                assertEquals(1, created.storage.processed_files)
                assertEquals(1, created.storage.total_rows_inserted)
                assertEquals(SYNTHETIC_PDF_NAME, created.extracted.results.single().fileName)
                val persisted = legacy()
                assertEquals(created.extracted, persisted)
                val deduped = Json.decodeFromString<UploadPriceProposalResponse>(pdf(uploadPdf(), 200))
                assertEquals(0, deduped.storage.processed_files)
                assertEquals(persisted, legacy())
                assertTrue(emptyExcel == snapshot(fixture), "PDF wrote to the Excel store")
                activate(port, done, books[0]); send(post(port, 1), 201, done)
                assertEquals(importOracleRows(), page(send(get(port), 200, done)).batches.single().proposals)
                assertEquals(persisted, legacy())
                val savedExcel = snapshot(fixture)
                pdf(uploadPdf(), 200)
                assertTrue(savedExcel == snapshot(fixture), "PDF replay wrote to the Excel store")
                assertEquals(1, repo.writes.get())
                val published = synchronized(notifications) { notifications.single() }
                assertEquals(PriceUpdatesEventType.PRICE_PROPOSALS_UPSERTED, published.eventType)
                assertEquals(1, published.changedCount)
                assertTrue(published.changedIds.isEmpty())
            }
        }
    }
}

private class SyntheticPdfPriceTableRepository : PriceTableRepositoryPort {
    private val saved = java.util.concurrent.ConcurrentHashMap<String, PriceTableResponse>()
    val writes = AtomicInteger()
    override fun storePriceTableResults(priceTableResponse: PriceTableResponse, sourceSha256: String?): Int {
        checkNotNull(sourceSha256)
        check(saved.putIfAbsent(sourceSha256, priceTableResponse) == null)
        writes.incrementAndGet()
        return priceTableResponse.results.size
    }
    override fun existsBySourceSha256(sourceSha256: String) = saved.containsKey(sourceSha256)
    override fun getAllPriceTableResults(tarifaType: String?) = PriceTableResponse(true, saved.values.flatMap { it.results })
    override fun getTaxSettings(): TaxSettingsResponse = error("PDF route touched taxes")
    override fun updateTaxSettings(iva: Double, impuestoElectrico: Double): TaxSettingsResponse = error("PDF route touched taxes")
    override fun getFilteredPriceTableResults(tarifaType: String?): FilteredPriceTableResponse = error("unexpected PDF endpoint")
    override fun clearAllData(): Int = error("unexpected PDF deletion")
    override fun deleteResultsByIds(ids: List<Int>): Pair<List<Int>, List<Int>> = error("unexpected PDF deletion")
}
