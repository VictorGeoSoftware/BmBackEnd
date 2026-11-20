package com.bm.backend.routes

import com.bm.backend.models.*
import com.bm.backend.services.*
import com.bm.backend.testing.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.sql.SQLException
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*

internal class ProdAnalogReleaseRoutesTest : TeachProposalHttpHarness() {
    private val pins = books.map { it.digest }.toSet()
    private val marked = markedImportWorkbook()
    private val incompatible = incompatibleImportWorkbook()
    private val postal = "/api/v1$PROD_ANALOG_POST"
    private val listing = "/api/v1$PROD_ANALOG_LIST"
    private val aDiff = projectTeachPromotion(checkNotNull(registry.exact("gesternova", books.first().revision)), registry.manifest)
    private val bDiff = projectTeachPromotion(checkNotNull(registry.exact("gesternova", books.last().revision)), registry.manifest)

    private fun load(port: ProdAnalogEndpoint, token: String, attempt: Int = 1, bytes: ByteArray = workbook, filename: String = name,
                     publisher: HttpRequest.BodyPublisher = HttpRequest.BodyPublishers.ofByteArray(bytes)) =
        port.request(postal, token).header("Content-Type", WORKBOOK_MEDIA_TYPE).header(WORKBOOK_FILENAME_HEADER, encoded(filename))
            .header(PROPOSAL_IMPORT_ATTEMPT_HEADER, id(attempt)).POST(publisher).build()
    private fun read(port: ProdAnalogEndpoint, token: String, query: String = "") = port.request(listing + query, token).GET().build()
    private fun ProdAnalogEndpoint.send(req: HttpRequest, status: Int, done: TeachCallCompletion): String =
        checked(http.send(req, HttpResponse.BodyHandlers.ofString()), status).also { done.await() }
    private fun analogReceipt(text: String) = Json.decodeFromString<ProdAnalogReceipt>(text)
    private fun analogPage(text: String) = Json.decodeFromString<ProdAnalogPage>(text)
    private fun await(signal: CountDownLatch) { assertTrue(signal.await(8, TimeUnit.SECONDS), "synthetic barrier expired") }
    private fun <T> async(work: () -> T) = CompletableFuture.supplyAsync(work)
    private fun <T> CompletableFuture<T>.settled() = get(10, TimeUnit.SECONDS)

    private fun snapshot(fixture: ProdAnalogPostgres, query: String): List<List<String?>> = buildList {
        fixture.owner { sql -> sql.createStatement().use { st -> st.executeQuery(query).use { r ->
            while (r.next()) add((1..r.metaData.columnCount).map(r::getString))
        } } }
    }
    private fun counts(db: ProdAnalogPostgres) = snapshot(db,
        "SELECT (SELECT count(*) FROM analog.release), (SELECT count(*) FROM analog.batch), (SELECT count(*) FROM analog.proposal)").single()
    private fun count(db: ProdAnalogPostgres, release: Int, batch: Int) = assertEquals(listOf(release, batch, batch * 3).map(Int::toString), counts(db))
    private fun assertExact(receipt: ProdAnalogReceipt, diff: TeachPromotionDiff, ordinal: Long) {
        assertEquals(PROD_ANALOG, receipt.environment); assertEquals(3, receipt.proposalCount)
        assertTrue(receipt.revisionId == diff.revision && receipt.artifactSha256 == diff.digest, "unexpected release attribution")
        assertEquals(ordinal.toString(), receipt.generation)
    }
    private fun assertRelease(db: ProdAnalogPostgres, attempt: ProdAnalogDeployment) {
        val expected = attempt.candidate.canonical
        val rows = snapshot(db, """SELECT schema, environment, store::text, authority::text, target::text, supplier,
            deployment::text, ordinal::text, revision, semantics, origin, digest, convert_from(artifact,'UTF8'),
            (registered_at <= clock_timestamp())::text FROM analog.release ORDER BY ordinal DESC LIMIT 1""")
        assertTrue(rows.single() == listOf(RELEASE_SCHEMA, PROD_ANALOG, db.binding.store.toString(), db.binding.authority.toString(),
            db.binding.destination.toString(), "gesternova", attempt.id.toString(), attempt.ordinal.toString(), expected.revision,
            "gesternova-fixed-extractor/1", registry.manifest, expected.digest, expected.artifact, "true"), "wrong release manifest tuple or bytes")
    }

    private fun service(db: ProdAnalogPostgres, writer: ProdAnalogImports = db.imports(),
                        reader: ProdAnalogReleaseReader = db.releaseReader(), catalog: ProdAnalogCatalog = ProdAnalogCatalog(pins),
                        afterPin: (ProdAnalogRelease) -> Unit = {}) =
        ProdAnalogImportService(db.binding, catalog, reader, writer, listOf(workbook, marked, incompatible), afterPin)

    /** Real QA activation, Excel commit, validation and promotion services with the existing fake PR boundary. */
    private fun withEvidence(run: (TeachActivationPostgres, ProdAnalogPostgres, TeachPromotionFake, TeachPromotionReceipt, TeachPromotionReceipt) -> Unit) {
        TeachPromotionFake(listOf(aDiff, bDiff)).use { fake ->
            TeachActivationPostgres(registry.manifest, withProposals = true, promotionProviderId = fake.fixtureId, withProdAnalog = true).use { qa ->
                val analog = checkNotNull(qa.prodAnalog)
                val fixture = checkNotNull(qa.proposals)
                qa.sentinel { qa.seed(it, "f".repeat(64), 19) }
                fun sentinel() = qa.sentinel { sql ->
                    val catalog = qa.snapshot(sql)
                    val opaque = sql.createStatement().use { st -> st.executeQuery("SELECT proposal_count, validation_count FROM proposals.canary CROSS JOIN proposals.validation").use {
                        check(it.next()); it.getInt(1) to it.getInt(2)
                    } }
                    catalog to opaque
                }
                val sentinelBefore = sentinel()
                val imported = assertNotNull(TeachProposalImportService.mount(assertNotNull(TeachSupplierSkillSelectionService.mount(qa.reader(), registry)), fixture.store()))
                val validation = assertNotNull(TeachProposalValidationService.mount(imported, fixture.validationStore()))
                val promotion = assertNotNull(TeachProductionPromotionService.mount(validation, registry, fixture.promotionStore(), fake))
                netty(imported, pilot(qa), validation = validation, promotion = promotion) { port, done ->
                    fun propose(index: Int) {
                        activate(port, done, books[index], books[0].takeIf { index == 1 })
                        send(post(port, index + 1), 201, done)
                        val page = Json.decodeFromString<TeachValidatedImportPage>(send(get(port, "?validation=1"), 200, done))
                        val item = page.batches.first()
                        assertEquals(books[index].revision, item.revisionId)
                        assertTrue(item.proposals == importOracleRows(), "QA import differs from approved oracle")
                        val ref = item.validationRef
                        fun confirm(path: String, flag: String) = request(port, "/api/v1$path", "fixture-token")
                            .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("""{"validationRef":"$ref","$flag":true}""")).build()
                        send(confirm(PROPOSAL_VALIDATION_PATH, "confirmExpectedPrices"), 201, done)
                        send(confirm(PRODUCTION_PROMOTION_PATH, "confirmPromotion"), 201, done)
                    }
                    propose(0); propose(1)
                }
                val beforeQa = qa.qaOwner(qa::snapshot)
                val beforeImports = snapshot(fixture)
                fun evidence() = buildList {
                    fixture.owner { sql -> for (table in listOf("validation", "promotion")) {
                        sql.createStatement().use { st -> st.executeQuery("SELECT row_to_json(t)::text FROM proposals.$table t ORDER BY 1").use { r ->
                            while (r.next()) add(r.getString(1))
                        } }
                    } }
                }
                val beforeEvidence = evidence()
                val (a, b) = fake.inspect()
                assertTrue(a.proposal.diff == aDiff && b.proposal.diff == bDiff && aDiff.digest != bDiff.digest && aDiff.revision != bDiff.revision)
                assertTrue(Json.parseToJsonElement(aDiff.artifact).jsonObject["rule"] == Json.parseToJsonElement(bDiff.artifact).jsonObject["rule"])
                count(analog, 0, 0)
                run(qa, analog, fake, a, b)
                assertTrue(beforeQa == qa.qaOwner(qa::snapshot) && beforeImports == snapshot(fixture) && beforeEvidence == evidence(), "analogue changed QA")
                assertTrue(sentinelBefore == sentinel(), "analogue changed the sentinel")
                assertTrue(fake.inspect() == listOf(a, b) && fake.protectedBase.isEmpty(), "deployment mutated the PR fake")
            }
        }
    }

    @Test fun `candidate Pages and Ktor import only successfully released revisions over loopback`() {
        assumeTrue(System.getenv("BM_TEACH_CLARIFY_TEST_WEB") != null, "The required joint runs separately with Node 20")
        withEvidence { qa, db, pr, a, b ->
            ProdAnalogMergeGate(pr).use { merge -> ProdAnalogFakeExecutor().use { executor -> ProdAnalogDeploymentClock().use { ordinals ->
                ProdAnalogAuthorization(db.binding).use { auth ->
                    val actor = auth.grant(); val other = auth.grant()
                    val preCommit = CountDownLatch(1); val continueCommit = CountDownLatch(1)
                    val hold = AtomicBoolean(true)
                    val authority = ProdAnalogDeploymentAuthority(db.binding, ProdAnalogCatalog(pins), merge, executor,
                        db.releaseWriter(beforeCommit = { if (hold.getAndSet(false)) { preCommit.countDown(); await(continueCommit) } }), ordinals)
                    fun joint(port: ProdAnalogEndpoint, phase: String, vararg attempts: ProdAnalogDeployment) = runProdAnalogPagesLoopback(mapOf(
                        "BM_PROD_ANALOG_TEST_PORT" to port.port.toString(), "BM_PROD_ANALOG_TEST_PIN" to port.pin,
                        "BM_PROD_ANALOG_TEST_ACTOR" to actor, "BM_PROD_ANALOG_TEST_OTHER" to other,
                        "BM_PROD_ANALOG_TEST_INPUT" to listOf(workbook, marked).joinToString(":", transform = Base64.getEncoder()::encodeToString),
                        "BM_PROD_ANALOG_TEST_PHASE" to phase,
                        "BM_PROD_ANALOG_TEST_EXPECT" to buildJsonArray { attempts.forEach { attempt -> add(buildJsonObject {
                            put("revisionId", attempt.candidate.canonical.revision); put("generation", attempt.ordinal.toString())
                            put("artifactSha256", attempt.candidate.canonical.digest)
                        }) } }.toString(),
                    ))
                    lateinit var deployA: ProdAnalogDeployment
                    lateinit var deployB: ProdAnalogDeployment
                    prodAnalogNetty(service(db), auth) { port, done ->
                        assertFailsWith<ProdAnalogUnavailable> { authority.begin(a) }
                        joint(port, "absent"); count(db, 0, 0) // Both promoted, B is QA active.
                        merge.closeUnmerged(a); assertFailsWith<ProdAnalogUnavailable> { authority.begin(a) }
                        joint(port, "absent"); count(db, 0, 0)
                        merge.reopen(a); merge.simulateMerge(a)
                        val failed = authority.begin(a)
                        assertFailsWith<ProdAnalogUnavailable> { authority.finish(failed) }
                        joint(port, "absent"); count(db, 0, 0)
                        executor.run(failed, ProdAnalogFakeExecutor.Outcome.PARTIAL)
                        assertFailsWith<ProdAnalogUnavailable> { authority.finish(failed) }
                        joint(port, "absent"); count(db, 0, 0)
                        deployA = authority.begin(a)
                        executor.run(deployA, ProdAnalogFakeExecutor.Outcome.SUCCESS)
                        val commit = async { authority.finish(deployA) }
                        try { await(preCommit); assertError(port.send(load(port, actor), 409, done), "no_released_skill"); count(db, 0, 0) }
                        finally { continueCommit.countDown() }
                        assertTrue(commit.settled()); assertRelease(db, deployA)
                        joint(port, "A", deployA); count(db, 1, 2)
                        assertFailsWith<ProdAnalogUnavailable> { authority.begin(b) }
                        merge.simulateMerge(b)
                        val failedB = authority.begin(b)
                        executor.run(failedB, ProdAnalogFakeExecutor.Outcome.FAILURE)
                        assertFailsWith<ProdAnalogUnavailable> { authority.finish(failedB) }
                        assertRelease(db, deployA)
                        deployB = authority.begin(b)
                        executor.run(deployB, ProdAnalogFakeExecutor.Outcome.SUCCESS)
                        assertTrue(authority.finish(deployB)); assertRelease(db, deployB)
                        val laterFailure = authority.begin(a)
                        executor.run(laterFailure, ProdAnalogFakeExecutor.Outcome.FAILURE)
                        assertFailsWith<ProdAnalogUnavailable> { authority.finish(laterFailure) }
                        assertRelease(db, deployB)
                        assertFalse(authority.finish(deployA)); count(db, 2, 2)
                    }
                    prodAnalogNetty(service(db), auth) { port, _ -> joint(port, "B", deployB, deployA) }
                    count(db, 2, 4)
                    val attribution = snapshot(db, """SELECT b.ordinal::text, (b.revision = r.revision AND b.digest = r.digest)::text,
                        (SELECT count(*) FROM analog.proposal p WHERE p.batch_id = b.id AND p.actor = b.actor)::text
                        FROM analog.batch b JOIN analog.release r USING (store, supplier, ordinal) ORDER BY b.id""")
                    assertEquals(listOf(deployA, deployA, deployB, deployB).map { listOf(it.ordinal.toString(), "true", "3") }, attribution)
                    val returnA = authority.begin(a)
                    executor.run(returnA, ProdAnalogFakeExecutor.Outcome.SUCCESS)
                    assertTrue(authority.finish(returnA)); assertRelease(db, returnA)
                    prodAnalogNetty(service(db), auth) { port, _ -> joint(port, "return-A", returnA, deployB, deployA) }
                    count(db, 3, 5)
                    db.owner { it.exec("UPDATE analog.release SET artifact = artifact || convert_to(' ', 'UTF8') WHERE ordinal = ${returnA.ordinal}") }
                    try {
                        prodAnalogNetty(service(db), auth) { port, _ -> joint(port, "invalid-head", returnA, deployB, deployA) }
                        count(db, 3, 5)
                    } finally {
                        db.owner { it.exec("UPDATE analog.release SET artifact = substring(artifact from 1 for octet_length(artifact)-1) WHERE ordinal = ${returnA.ordinal}") }
                    }
                    acl(qa, db)
                }
            } } }
        }
    }

    @Test fun `ordinal races loss reconciliation exact head and new release return never select historical fallback`() {
        withEvidence { _, db, pr, a, b ->
            ProdAnalogMergeGate(pr).use { merge -> ProdAnalogFakeExecutor().use { executor -> ProdAnalogDeploymentClock().use { ordinals ->
                ProdAnalogAuthorization(db.binding).use { auth ->
                    val actor = auth.grant()
                    val pin = CountDownLatch(1); val resumePin = CountDownLatch(1); val holdPin = AtomicBoolean()
                    val fault = AtomicReference("")
                    fun failAt(stage: String) = { if (fault.compareAndSet(stage, "")) throw SQLException("synthetic commit response interrupted") }
                    fun controller() = ProdAnalogDeploymentAuthority(db.binding, ProdAnalogCatalog(pins), merge, executor,
                        db.releaseWriter(failAt("before"), failAt("after")), ordinals)
                    var authority = controller()
                    merge.simulateMerge(a); merge.simulateMerge(b)
                    val first = authority.begin(a)
                    executor.run(first, ProdAnalogFakeExecutor.Outcome.SUCCESS)
                    fault.set("before"); assertFailsWith<ProdAnalogUnavailable> { authority.finish(first) }; count(db, 0, 0)
                    fault.set("after"); assertFailsWith<ProdAnalogUnavailable> { authority.finish(first) }; count(db, 1, 0)
                    authority = controller()
                    assertFalse(authority.finish(first)) // Same attempt only; no re-execution and no new manifest.
                    val older = authority.begin(a)
                    val newer = authority.begin(b)
                    val unwitnessed = ProdAnalogDeployment(newer.id, newer.ordinal, newer.binding, newer.target, newer.candidate)
                    executor.run(unwitnessed, ProdAnalogFakeExecutor.Outcome.SUCCESS)
                    assertFailsWith<ProdAnalogUnavailable> { authority.finish(unwitnessed) }
                    val wrongDestination = ProdAnalogDeployment(newer.id, newer.ordinal, newer.binding.copy(destination = UUID.randomUUID()), newer.target, newer.candidate)
                    executor.run(wrongDestination, ProdAnalogFakeExecutor.Outcome.SUCCESS)
                    assertFailsWith<ProdAnalogUnavailable> { authority.finish(wrongDestination) }
                    executor.run(newer, ProdAnalogFakeExecutor.Outcome.SUCCESS)
                    assertFailsWith<ProdAnalogUnavailable> { authority.finish(newer, aDiff.artifact.encodeToByteArray(), aDiff.digest) }
                    assertFailsWith<ProdAnalogUnavailable> { authority.finish(first, bDiff.artifact.encodeToByteArray(), bDiff.digest) }
                    count(db, 1, 0)
                    val applied = AtomicInteger()
                    prodAnalogNetty(service(db, afterPin = {
                        applied.incrementAndGet()
                        if (holdPin.getAndSet(false)) { pin.countDown(); await(resumePin) }
                    }), auth) { port, done ->
                        holdPin.set(true)
                        val lateImport = port.http.sendAsync(load(port, actor), HttpResponse.BodyHandlers.ofString())
                        try {
                            await(pin)
                            assertTrue(authority.finish(newer)); assertRelease(db, newer)
                            executor.run(older, ProdAnalogFakeExecutor.Outcome.SUCCESS) // older executor completes after newer commit
                            assertFailsWith<ProdAnalogUnavailable> { authority.finish(older) }
                            assertFalse(authority.finish(first))
                            assertExact(analogReceipt(port.send(load(port, actor, 2), 201, done)), bDiff, newer.ordinal)
                        } finally { resumePin.countDown() }
                        val saved = analogReceipt(checked(lateImport.settled(), 201)); done.await(); assertExact(saved, aDiff, first.ordinal)
                        val replay = analogReceipt(port.send(load(port, actor), 200, done))
                        assertTrue(saved.copy(kind = "alreadyStored") == replay)
                        assertEquals(2, applied.get()); count(db, 2, 2)
                        val revert = authority.begin(a)
                        executor.run(revert, ProdAnalogFakeExecutor.Outcome.SUCCESS)
                        assertTrue(authority.finish(revert)); assertRelease(db, revert)
                        assertExact(analogReceipt(port.send(load(port, actor, 3), 201, done)), aDiff, revert.ordinal)
                        val order = analogPage(port.send(read(port, actor), 200, done)).batches
                        assertEquals(setOf(first.ordinal, newer.ordinal, revert.ordinal).map(Long::toString).toSet(), order.map { it.generation }.toSet())
                        assertTrue(order.all { it.proposals == importOracleRows() }, "stored values or citations differ from oracle")
                        for (corruption in listOf("semantics = 'other'" to "semantics = 'gesternova-fixed-extractor/1'",
                            "origin = repeat('0',64)" to "origin = '${registry.manifest}'",
                            "target = '00000000-0000-0000-0000-000000000001'" to "target = '${db.binding.destination}'",
                            "artifact = artifact || convert_to(' ', 'UTF8')" to "artifact = substring(artifact from 1 for octet_length(artifact)-1)")) {
                            db.owner { it.exec("UPDATE analog.release SET ${corruption.first} WHERE ordinal = ${revert.ordinal}") }
                            assertEquals("", port.send(load(port, actor, 4), 503, done))
                            count(db, 3, 3)
                            db.owner { it.exec("UPDATE analog.release SET ${corruption.second} WHERE ordinal = ${revert.ordinal}") }
                        }
                        assertEquals(3, applied.get())
                    }
                    assertFailsWith<ProdAnalogUnavailable> { service(db, reader = db.releaseReader(db.binding.copy(store = UUID.randomUUID()))) }
                    assertFailsWith<ProdAnalogUnavailable> { service(db, reader = db.releaseReader(asRole = ProdAnalogPostgres.Role.LIST)) }
                    // Same physical DB and exact head, but no compatible V2 executable in the independent resolver.
                    val onlyA = ProdAnalogCatalog(setOf(books.first().digest))
                    val newB = authority.begin(b)
                    executor.run(newB, ProdAnalogFakeExecutor.Outcome.SUCCESS); assertTrue(authority.finish(newB))
                    prodAnalogNetty(service(db), auth) { port, done ->
                        db.owner { it.exec("UPDATE analog.release SET digest = repeat('0',64) WHERE ordinal = ${newB.ordinal}") }
                        assertEquals("", port.send(load(port, actor, 4), 503, done)); count(db, 4, 3)
                        db.owner { it.exec("UPDATE analog.release SET digest = '${bDiff.digest}' WHERE ordinal = ${newB.ordinal}") }
                        db.owner { it.exec("UPDATE analog.identity SET schema_version = 2") }
                        assertEquals("", port.send(read(port, actor), 503, done))
                        db.owner { it.exec("UPDATE analog.identity SET schema_version = 1") }
                    }
                    prodAnalogNetty(service(db, catalog = onlyA), auth) { port, done ->
                        assertEquals("", port.send(load(port, actor, 4), 503, done)); count(db, 4, 3)
                        assertExact(analogReceipt(port.send(load(port, actor), 200, done)), aDiff, first.ordinal)
                    }
                }
            } } }
        }
    }

    @Test fun `four admitted verification workers keep their slots until actual cleanup`() {
        TeachActivationPostgres(registry.manifest, withProdAnalog = true).use { setup ->
            val db = checkNotNull(setup.prodAnalog)
            val blocked = AtomicBoolean(true)
            val started = CountDownLatch(4); val release = CountDownLatch(1); val exited = CountDownLatch(4)
            ProdAnalogAuthorization(db.binding) {
                if (blocked.get()) { started.countDown(); try { assertTrue(release.await(24, TimeUnit.SECONDS)) } finally { exited.countDown() } }
            }.use { auth ->
                val actor = auth.grant(); val received = AtomicInteger()
                prodAnalogNetty(service(db), auth, received) { port, done ->
                    val waiting = List(4) { port.http.sendAsync(load(port, actor), HttpResponse.BodyHandlers.ofString()) }
                    try {
                        await(started)
                        port.send(read(port, actor), 429, done)
                        waiting.forEach { assertEquals("", checked(it.get(17, TimeUnit.SECONDS), 408)); done.await() }
                        assertEquals(4L, exited.count)
                        port.send(read(port, actor), 429, done)
                        count(db, 0, 0); assertEquals(0, received.get())
                    } finally { blocked.set(false); release.countDown() }
                    await(exited)
                    val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
                    while (true) {
                        val probe = port.http.send(read(port, actor), HttpResponse.BodyHandlers.ofString()); done.await()
                        if (probe.statusCode() == 200) break
                        assertEquals(429, probe.statusCode()); assertTrue(System.nanoTime() < until, "admission did not return after cleanup")
                    }
                }
            }
        }
    }

    @Test fun `fixture decoder rejects noncanonical framing before admission while single length and chunked reach import`() {
        TeachActivationPostgres(registry.manifest, withProdAnalog = true).use { setup ->
            val db = checkNotNull(setup.prodAnalog)
            val authCalls = AtomicInteger()
            val receives = AtomicInteger()
            ProdAnalogAuthorization(db.binding) { authCalls.incrementAndGet() }.use { auth ->
                val token = auth.grant()
                prodAnalogNetty(service(db), auth, receives) { endpoint, done ->
                    // Header-only test matrix: no body, second request, or attack payload is sent.
                    for (framing in listOf(
                        listOf("Content-Length: 1", "Transfer-Encoding: chunked"),
                        listOf("Transfer-Encoding: chunked", "Content-Length: 1"),
                        listOf("Content-Length: 1", "Content-Length: 1"),
                        listOf("Content-Length: 01"),
                        listOf("Transfer-Encoding: chunked", "Transfer-Encoding: chunked"),
                    )) endpoint.socket().use { socket ->
                        socket.startHandshake()
                        val fields = listOf("POST $postal HTTP/1.1", "Host: 127.0.0.1", "Connection: close",
                            "Authorization: Bearer $token", "Content-Type: $WORKBOOK_MEDIA_TYPE",
                            "$WORKBOOK_FILENAME_HEADER: ${encoded(name)}", "$PROPOSAL_IMPORT_ATTEMPT_HEADER: ${id(1)}",
                            "Expect: 100-continue", "X-Bm-Analog-Challenge: ${id(2)}") + framing
                        socket.outputStream.write(fields.joinToString("\r\n", postfix = "\r\n\r\n").encodeToByteArray())
                        socket.outputStream.flush()
                        assertEquals("HTTP/1.1 400 Bad Request", socket.inputStream.bufferedReader(Charsets.US_ASCII).readLine())
                    }
                    assertEquals(0, authCalls.get()); assertEquals(0, receives.get()); count(db, 0, 0)
                    assertError(endpoint.send(load(endpoint, token), 409, done), "no_released_skill")
                    val chunked = HttpRequest.BodyPublishers.fromPublisher(HttpRequest.BodyPublishers.ofByteArray(workbook))
                    assertError(endpoint.send(load(endpoint, token, publisher = chunked), 409, done), "no_released_skill")
                    assertEquals(2, receives.get()); count(db, 0, 0)
                }
            }
        }
    }

    @Test fun `scoped record replay concurrent claims rollback and revocation keep only whole commits`() {
        withEvidence { qa, db, pr, a, _ ->
            ProdAnalogMergeGate(pr).use { merge -> ProdAnalogFakeExecutor().use { executor -> ProdAnalogDeploymentClock().use { ordinals ->
                ProdAnalogAuthorization(db.binding).use { auth ->
                    val actor = auth.grant(); val other = auth.grant()
                    val authority = ProdAnalogDeploymentAuthority(db.binding, ProdAnalogCatalog(pins), merge, executor, db.releaseWriter(), ordinals)
                    merge.simulateMerge(a); val deployed = authority.begin(a)
                    executor.run(deployed, ProdAnalogFakeExecutor.Outcome.SUCCESS); assertTrue(authority.finish(deployed))
                    val at = AtomicReference(""); val point = AtomicReference(CountDownLatch(0)); val resume = AtomicReference(CountDownLatch(0))
                    fun park(stage: String) { if (at.compareAndSet(stage, "")) { point.get().countDown(); await(resume.get()) } }
                    val fault = AtomicReference(""); val received = AtomicInteger()
                    val imports = db.imports(afterRow = { row -> if (row == 16) {
                        if (fault.compareAndSet("partial", "")) throw SQLException("synthetic row rollback")
                        park("write")
                    } }, afterCommit = { if (fault.compareAndSet("lost", "")) throw SQLException("synthetic lost receipt"); park("committed") },
                        beforeReadReturn = { park("read") })
                    val sync = AtomicBoolean(); val arrivals = CountDownLatch(2); val leave = CountDownLatch(1)
                    val app = service(db, imports, afterPin = { if (sync.get()) { arrivals.countDown(); await(leave) } })
                    prodAnalogNetty(app, auth, received) { port, done ->
                        val before = received.get()
                        port.send(load(port, "fixture-token"), 401, done)
                        val bad = port.request(postal, actor).header("Content-Type", WORKBOOK_MEDIA_TYPE).header(WORKBOOK_FILENAME_HEADER, encoded(name))
                            .header(PROPOSAL_IMPORT_ATTEMPT_HEADER, id(1)).header("X-Teach-Revision", "override")
                            .POST(HttpRequest.BodyPublishers.ofByteArray(workbook)).build()
                        port.send(bad, 400, done); port.send(read(port, actor, "?cursor=01"), 400, done)
                        assertEquals(before, received.get())
                        assertError(port.send(load(port, actor, bytes = incompatible), 409, done), "incompatible_workbook")
                        assertError(port.send(load(port, actor, bytes = byteArrayOf(1)), 409, done), "incompatible_workbook")
                        assertError(port.send(load(port, actor, bytes = marked, filename = "bm-pilot-luz-fixture.xlsx"), 409, done), "conflicting_supplier")
                        fault.set("partial"); port.send(load(port, actor), 503, done); count(db, 1, 0)
                        sync.set(true)
                        val both = List(2) { port.http.sendAsync(load(port, actor), HttpResponse.BodyHandlers.ofString()) }
                        try { await(arrivals); count(db, 1, 0) } finally { sync.set(false); leave.countDown() }
                        val receipts = both.map { it.settled().also { done.await() } }.sortedBy { it.statusCode() }
                        val original = analogReceipt(checked(receipts[1], 201)); assertTrue(original.copy(kind = "alreadyStored") == analogReceipt(checked(receipts[0], 200)))
                        count(db, 1, 1)
                        assertTrue(analogPage(port.send(read(port, other), 200, done)).batches.isEmpty())
                        assertError(port.send(load(port, actor, bytes = marked, filename = "neutral.xlsx"), 409, done), "attempt_mismatch")
                        fun revoke(stage: String, req: HttpRequest, beforeRelease: () -> Unit) {
                            point.set(CountDownLatch(1)); resume.set(CountDownLatch(1)); at.set(stage)
                            val result = port.http.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                            try { await(point.get()); beforeRelease(); auth.revoke(actor); resume.get().countDown()
                                assertEquals("", checked(result.settled(), 403)); done.await()
                            } finally { auth.restore(actor); resume.get().countDown() }
                        }
                        revoke("write", load(port, actor, 2)) { count(db, 1, 1) }
                        count(db, 1, 1)
                        revoke("committed", load(port, actor, 2)) { count(db, 1, 2) }
                        revoke("read", read(port, actor)) { count(db, 1, 2) }
                        assertExact(analogReceipt(port.send(load(port, actor, 2), 200, done)), aDiff, deployed.ordinal)
                        fault.set("lost"); port.send(load(port, actor, 3), 503, done); count(db, 1, 3)
                        assertExact(analogReceipt(port.send(load(port, actor, 3), 200, done)), aDiff, deployed.ordinal)
                        port.send(load(port, other), 201, done)
                        port.send(load(port, actor, 4), 201, done); port.send(load(port, actor, 5), 201, done)
                        val first = analogPage(port.send(read(port, actor), 200, done)); assertEquals(4, first.batches.size)
                        val second = analogPage(port.send(read(port, actor, "?cursor=${first.nextCursor}"), 200, done))
                        assertTrue(second.batches.single().importId == original.importId)
                        assertTrue(analogPage(port.send(read(port, other, "?cursor=${first.nextCursor}"), 200, done)).batches.isEmpty())
                    }
                    db.owner { it.exec("UPDATE analog.identity SET schema_version = 2") }
                    assertFailsWith<ProdAnalogUnavailable> { service(db) }
                    acl(qa, db)
                }
            } } }
        }
    }

    private fun acl(qa: TeachActivationPostgres, db: ProdAnalogPostgres) {
        fun denied(role: ProdAnalogPostgres.Role, query: String) = db.roleConnection(role).use { conn ->
            assertEquals("42501", assertFailsWith<SQLException> { conn.exec(query) }.sqlState)
        }
        for (role in ProdAnalogPostgres.Role.entries) {
            for (foreign in listOf(qa.qaDb, qa.sentinelDb, checkNotNull(qa.proposals).db, "postgres", "template1", "fixture_admin")) {
                assertEquals("42501", assertFailsWith<SQLException> { db.roleConnection(role, foreign).close() }.sqlState)
            }
            denied(role, "UPDATE analog.release SET semantics = 'other'")
            denied(role, "DELETE FROM analog.release"); denied(role, "TRUNCATE analog.release")
            denied(role, "CREATE TABLE analog.foreign_table (id integer)")
            denied(role, "SET ROLE fixture_owner")
            if (role != ProdAnalogPostgres.Role.DEPLOY) denied(role, "INSERT INTO analog.release (supplier) VALUES ('gesternova')")
            if (role != ProdAnalogPostgres.Role.IMPORT) denied(role, "INSERT INTO analog.batch (attempt) VALUES ('synthetic')")
        }
        for (role in listOf(ProdAnalogPostgres.Role.DEPLOY, ProdAnalogPostgres.Role.RESOLVE)) {
            denied(role, "SELECT actor FROM analog.batch"); denied(role, "SELECT result FROM analog.proposal")
        }
        for (role in listOf(ProdAnalogPostgres.Role.IMPORT, ProdAnalogPostgres.Role.LIST)) denied(role, "SELECT artifact FROM analog.release")
        denied(ProdAnalogPostgres.Role.LIST, "SELECT attempt FROM analog.batch")
        denied(ProdAnalogPostgres.Role.IMPORT, "SELECT result FROM analog.proposal")
        val proposals = checkNotNull(qa.proposals)
        assertEquals("42501", assertFailsWith<SQLException> { qa.qaConnection(db.database).close() }.sqlState)
        assertEquals("42501", assertFailsWith<SQLException> { qa.readerConnection(db.database).close() }.sqlState)
        assertEquals("42501", assertFailsWith<SQLException> { proposals.validationConnection(db.database).close() }.sqlState)
        assertEquals("42501", assertFailsWith<SQLException> { proposals.promotionConnection(true, db.database).close() }.sqlState)
        db.roleConnection(ProdAnalogPostgres.Role.IMPORT).use { conn ->
            conn.prepareStatement("""INSERT INTO analog.batch (actor, attempt, fingerprint, store, supplier, ordinal, revision, digest)
                VALUES (?, 'fixture-fk', 'synthetic', ?, 'gesternova', ?, ?, ?)""").use { ps ->
                ps.setObject(1, UUID.randomUUID()); ps.setObject(2, db.binding.store); ps.setLong(3, Long.MAX_VALUE)
                ps.setString(4, aDiff.revision); ps.setString(5, aDiff.digest)
                assertEquals("23503", assertFailsWith<SQLException> { ps.executeUpdate() }.sqlState)
            }
        }
    }
}
