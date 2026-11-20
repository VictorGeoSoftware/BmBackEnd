package com.bm.backend.routes

import com.bm.backend.models.*
import com.bm.backend.repositories.ports.TeachPromotionProviderUnavailable
import com.bm.backend.services.*
import com.bm.backend.testing.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.postgresql.util.PSQLException
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.sql.SQLException
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*

internal class TeachProductionPromotionRoutesTest : TeachProposalHttpHarness() {
    private val path = "/api/v1$PRODUCTION_PROMOTION_PATH"
    private val absent = "00000000-0000-4000-8000-000000000001"

    private fun fake(beforeLookup: (() -> Unit) -> Unit = {},
        afterCreate: (TeachPromotionReceipt, () -> Unit) -> TeachPromotionReceipt = { r, _ -> r }) = TeachPromotionFake(
        books.map { projectTeachPromotion(checkNotNull(registry.exact("gesternova", it.revision)), registry.manifest) }, beforeLookup, afterCreate)

    private fun imports(db: TeachActivationPostgres, fixture: TeachProposalPostgres) = assertNotNull(TeachProposalImportService.mount(
        assertNotNull(TeachSupplierSkillSelectionService.mount(db.reader(), registry)), fixture.store()))

    private fun list(port: Int, done: TeachCallCompletion) = Json.decodeFromString<TeachValidatedImportPage>(send(get(port, "?validation=1"), 200, done)).batches
    private fun result(text: String) = Json.decodeFromString<TeachPromotionResult>(text)
    private fun confirm(port: Int, ref: String, token: String = "fixture-token",
        text: String = """{"validationRef":"$ref","confirmPromotion":true}""") = request(port, path, token)
        .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(text)).build()
    private fun read(port: Int, ref: String, token: String = "fixture-token") = request(port, path, token).header(TEACH_PROMOTION_REF, ref).GET().build()
    private fun validate(port: Int, done: TeachCallCompletion, ref: String) = send(request(port, "/api/v1$PROPOSAL_VALIDATION_PATH", "fixture-token")
        .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("""{"validationRef":"$ref","confirmExpectedPrices":true}""")).build(), 201, done)

    private fun rows(fixture: TeachProposalPostgres, query: String): List<List<String?>> = buildList {
        fixture.owner { sql -> sql.createStatement().use { st -> st.executeQuery(query).use { r ->
            while (r.next()) add((1..r.metaData.columnCount).map(r::getString))
        } } }
    }
    private fun audit(fixture: TeachProposalPostgres) = rows(fixture, """SELECT b.revision, b.generation, p.op_key, p.digest, p.proposal_id,
        (p.actor = v.actor AND p.validation_ref = v.validation_ref)::text FROM proposals.promotion p
        JOIN proposals.validation v USING (validation_ref, actor) JOIN proposals.batch b USING (validation_ref, actor)
        ORDER BY b.id""")
    private fun sentinel(db: TeachActivationPostgres) = db.sentinel { sql -> db.snapshot(sql) to
        sql.createStatement().use { st -> st.executeQuery("SELECT proposal_count, validation_count FROM proposals.canary CROSS JOIN proposals.validation").use {
            check(it.next()); it.getInt(1) to it.getInt(2)
        } } }
    private fun await(latch: CountDownLatch) = assertTrue(latch.await(8, TimeUnit.SECONDS), "fixture barrier expired")

    @Test fun `candidate Pages proxy and Ktor propose exact validated QA revision over loopback`() {
        assumeTrue(System.getenv("BM_TEACH_CLARIFY_TEST_WEB") != null, "The required joint runs separately with Node 20")
        val fake = fake()
        TeachActivationPostgres(registry.manifest, withProposals = true, promotionProviderId = fake.fixtureId).use { db ->
            val fixture = checkNotNull(db.proposals)
            db.sentinel { db.seed(it, "f".repeat(64), 19) }
            val beforeSentinel = sentinel(db)
            val imports = imports(db, fixture)
            val validation = assertNotNull(TeachProposalValidationService.mount(imports, fixture.validationStore()))
            val promotion = assertNotNull(TeachProductionPromotionService.mount(validation, registry, fixture.promotionStore(), fake))
            lateinit var a: TeachValidatedImportBatch
            lateinit var original: TeachPromotionResult
            netty(imports, pilot(db), validation = validation, promotion = promotion) { port, done ->
                activate(port, done, books[0]); send(post(port, 1), 201, done)
                val target = list(port, done).single()
                assertEquals(importOracleRows(), target.proposals)
                validate(port, done, target.validationRef)
                activate(port, done, books[1], books[0]); send(post(port, 2), 201, done)
                val (b, confirmed) = list(port, done)
                a = confirmed
                val qa = db.qaOwner(db::snapshot)
                val proposalRows = snapshot(fixture)
                val validations = rows(fixture, "SELECT result, validated_at FROM proposals.validation")
                runTeachPagesLoopback("chains exact promotion Pages proxy and Ktor over loopback", mapOf(
                    "BM_TEACH_PROMOTION_TEST_PORT" to port.toString(), "BM_TEACH_IMPORT_PILOT" to "true", "BM_TEACH_VALIDATION_PILOT" to "true",
                    "BM_TEACH_PROMOTION_PILOT" to "true", "BM_TEACH_PROMOTION_TEST_A" to a.revisionId,
                    "BM_TEACH_PROMOTION_TEST_B" to b.revisionId), "production-promotion")
                original = result(send(read(port, a.validationRef), 200, done))
                assertEquals(qa, db.qaOwner(db::snapshot)); assertEquals(proposalRows, snapshot(fixture))
                assertEquals(validations, rows(fixture, "SELECT result, validated_at FROM proposals.validation"))
                assertEquals(1, fake.lookups.get()); assertEquals(1, fake.creates.get())
                val pr = fake.inspect().single()
                assertEquals("skills-fixture/gesternova/${a.revisionId}.json", pr.proposal.diff.path)
                assertEquals("protected-fixture", pr.proposal.base); assertEquals("teach-pilot/${pr.proposal.key}", pr.proposal.head)
                assertTrue(fake.protectedBase.isEmpty())
                assertEquals(listOf(a.revisionId, "1", pr.proposal.key.toString(), pr.proposal.diff.digest, pr.id.toString(), "true"), audit(fixture).single())
                assertEquals("https://pr-pilot.invalid/bm-pilot/skills-fixture/pull/${pr.id}", original.url)
            }
            val restartedImports = imports(db, fixture)
            val restartedValidation = assertNotNull(TeachProposalValidationService.mount(restartedImports, fixture.validationStore()))
            val restarted = assertNotNull(TeachProductionPromotionService.mount(restartedValidation, registry, fixture.promotionStore(), fake))
            netty(restartedImports, validation = restartedValidation, promotion = restarted) { port, done ->
                assertEquals(original, result(send(read(port, a.validationRef), 200, done)))
                assertEquals(original, result(send(confirm(port, a.validationRef), 200, done)))
            }
            assertEquals(1, fake.lookups.get()); assertEquals(beforeSentinel, sentinel(db))
        }
    }

    @Test fun `uncertain reservation commit and provider response reconcile only with persisted key and committed finish`() {
        val stage = AtomicReference("")
        val fake = fake(beforeLookup = {
            if (stage.get() == "lookup") throw TeachPromotionProviderUnavailable()
        }, afterCreate = { receipt, _ ->
            if (stage.get() == "receipt") receipt.copy(id = -1) else receipt
        })
        TeachActivationPostgres(registry.manifest, true, fake.fixtureId).use { db ->
            val fixture = checkNotNull(db.proposals)
            fun fault(at: String) = { if (stage.get() == at) throw SQLException("synthetic failure", "XX000") }
            val store = fixture.promotionStore(fault("before-reserve"), fault("after-reserve"), fault("before-finish"), fault("after-finish"))
            val imports = imports(db, fixture)
            val validation = assertNotNull(TeachProposalValidationService.mount(imports, fixture.validationStore()))
            val promotion = assertNotNull(TeachProductionPromotionService.mount(validation, registry, store, fake))
            assertNull(TeachProductionPromotionService.mount(validation, registry, store, fake()))
            assertNull(TeachProductionPromotionService.mount(validation, TeachActivationRegistry(books.map { it.digest }.toSet()), store, fake))
            assertNull(TeachProductionPromotionService.mount(validation, registry, fixture.promotionStore(descriptor = fixture.identity.copy(storeId = "1".repeat(32))), fake))
            val revoked = AtomicBoolean()
            netty(imports, pilot(db), revoked = revoked, validation = validation, promotion = promotion) { port, done ->
                activate(port, done, books[0]); send(post(port, 1), 201, done)
                val a = list(port, done).single()
                validate(port, done, a.validationRef)
                fun getState(kind: String) = assertEquals(kind, result(send(read(port, a.validationRef), 200, done)).kind)
                stage.set("before-reserve"); send(confirm(port, a.validationRef), 503, done)
                getState("notRequested"); assertTrue(audit(fixture).isEmpty()); assertEquals(0, fake.lookups.get())
                stage.set("after-reserve"); send(confirm(port, a.validationRef), 503, done)
                getState("pending"); val reserved = audit(fixture).single(); assertNull(reserved[4]); assertEquals(0, fake.lookups.get())
                stage.set("lookup"); send(confirm(port, a.validationRef), 502, done)
                getState("pending"); assertEquals(0, fake.creates.get())
                stage.set("receipt"); send(confirm(port, a.validationRef), 502, done)
                getState("pending"); assertEquals(reserved, audit(fixture).single()); assertEquals(reserved[2], fake.inspect().single().proposal.key.toString())
                stage.set("before-finish"); send(confirm(port, a.validationRef), 503, done)
                getState("pending"); assertEquals(1, fake.creates.get()); assertEquals(reserved, audit(fixture).single())
                stage.set("after-finish"); send(confirm(port, a.validationRef), 503, done)
                getState("opened"); assertEquals(1, fake.creates.get())
                revoked.set(true); send(read(port, a.validationRef), 403, done); send(confirm(port, a.validationRef), 403, done)
                revoked.set(false); assertEquals(result(send(read(port, a.validationRef), 200, done)), result(send(confirm(port, a.validationRef), 200, done)))
                assertEquals(fake.inspect().single().id.toString(), audit(fixture).single()[4]); assertEquals(1, fake.creates.get())
            }
        }
    }

    @Test fun `provider is outside SQL and concurrent requests share one committed promotion while other loads do not inherit`() {
        val accepted = CountDownLatch(1); val release = CountDownLatch(1)
        val fake = fake(afterCreate = { receipt, _ -> accepted.countDown(); await(release); receipt })
        TeachActivationPostgres(registry.manifest, true, fake.fixtureId).use { db ->
            val fixture = checkNotNull(db.proposals)
            val applyEntered = CountDownLatch(1); val applyRelease = CountDownLatch(1); val late = AtomicBoolean()
            val eventEntered = CountDownLatch(1); val eventRelease = CountDownLatch(1); val delayValidation = AtomicBoolean()
            val selector = assertNotNull(TeachSupplierSkillSelectionService.mount(db.reader(), registry))
            val imports = assertNotNull(TeachProposalImportService.mount(selector, fixture.store(afterRow = { row ->
                if (row == 16 && late.getAndSet(false)) { applyEntered.countDown(); await(applyRelease) }
            })))
            val validation = assertNotNull(TeachProposalValidationService.mount(imports, fixture.validationStore(beforeCommit = {
                if (delayValidation.getAndSet(false)) { eventEntered.countDown(); await(eventRelease) }
            })))
            val promotion = assertNotNull(TeachProductionPromotionService.mount(validation, registry, fixture.promotionStore(), fake))
            netty(imports, pilot(db), validation = validation, promotion = promotion) { port, done ->
                activate(port, done, books[0]); send(post(port, 1), 201, done)
                val a = list(port, done).single()
                late.set(true)
                val lateA = http.sendAsync(post(port, 2), HttpResponse.BodyHandlers.ofString())
                try { await(applyEntered); activate(port, done, books[1], books[0]) }
                finally { applyRelease.countDown() }
                assertEquals(books[0].revision to "1", receipt(checked(lateA.get(8, TimeUnit.SECONDS), 201)).let { it.revisionId to it.generation }); done.await()
                val secondA = list(port, done).first()
                delayValidation.set(true)
                val event = CompletableFuture.runAsync { validate(port, done, a.validationRef) }
                try {
                    await(eventEntered)
                    send(read(port, a.validationRef), 404, done); send(confirm(port, a.validationRef), 404, done)
                    assertEquals(0, fake.lookups.get()); assertTrue(audit(fixture).isEmpty())
                } finally { eventRelease.countDown() }
                event.get(8, TimeUnit.SECONDS)
                val first = http.sendAsync(confirm(port, a.validationRef), HttpResponse.BodyHandlers.ofString())
                try {
                    await(accepted)
                    assertEquals(0, rows(fixture, "SELECT pid FROM pg_stat_activity WHERE usename LIKE 'promotion_%'").size)
                    assertEquals("pending", result(send(read(port, a.validationRef), 200, done)).kind)
                    send(read(port, secondA.validationRef), 404, done)
                    val concurrent = result(send(confirm(port, a.validationRef), 201, done))
                    release.countDown()
                    assertEquals(concurrent, result(checked(first.get(8, TimeUnit.SECONDS), 200))); done.await()
                    assertEquals(1, fake.inspect().size); assertEquals(1, fake.creates.get())
                    send(post(port, 3), 201, done)
                    val b = list(port, done).first(); send(confirm(port, b.validationRef), 404, done)
                    validate(port, done, b.validationRef)
                    val resultB = result(send(confirm(port, b.validationRef), 201, done))
                    assertEquals(b.revisionId, resultB.revisionId); assertNotEquals(concurrent.url, resultB.url)
                    val diffs = fake.inspect().map { it.proposal.diff }
                    val artifacts = diffs.map { Json.parseToJsonElement(it.artifact).jsonObject }
                    assertEquals(artifacts[0]["rule"], artifacts[1]["rule"])
                    assertNotEquals(diffs[0].digest, diffs[1].digest)
                    assertEquals(listOf(a.revisionId, b.revisionId), audit(fixture).map { it[0] })
                    // This historical A load itself committed after B activation. Its prior 404 was not inherited approval.
                    validate(port, done, secondA.validationRef)
                    val third = result(send(confirm(port, secondA.validationRef), 201, done))
                    assertEquals(a.revisionId, third.revisionId); assertNotEquals(concurrent.url, third.url)
                    assertEquals(diffs[0], fake.inspect().last().proposal.diff)
                } finally { release.countDown() }
            }
        }
    }

    @Test fun `four shared slots hold until work closes and a blocked SQL read obeys the common deadline`() {
        val entered = CountDownLatch(4); val release = CountDownLatch(1)
        val hold = AtomicBoolean(true)
        val fake = fake(beforeLookup = { if (hold.get()) { entered.countDown(); await(release) } })
        TeachActivationPostgres(registry.manifest, true, fake.fixtureId).use { db ->
            val fixture = checkNotNull(db.proposals)
            val imports = imports(db, fixture)
            val validation = assertNotNull(TeachProposalValidationService.mount(imports, fixture.validationStore()))
            val promotion = assertNotNull(TeachProductionPromotionService.mount(validation, registry, fixture.promotionStore(), fake))
            netty(imports, pilot(db), validation = validation, promotion = promotion) { port, done ->
                activate(port, done, books[0]); send(post(port, 1), 201, done)
                val ref = list(port, done).single().validationRef; validate(port, done, ref)
                val waiting = List(4) { http.sendAsync(confirm(port, ref), HttpResponse.BodyHandlers.ofString()) }
                try {
                    await(entered)
                    send(read(port, ref), 429, done)
                    // Another Enséñame operation cannot bypass the admission semaphore.
                    send(get(port), 429, done)
                    assertEquals(0, rows(fixture, "SELECT pid FROM pg_stat_activity WHERE usename LIKE 'promotion_%'").size)
                } finally { hold.set(false); release.countDown() }
                val settled = waiting.map { it.get(8, TimeUnit.SECONDS).also { done.await() } }
                assertEquals(listOf(200, 200, 200, 201), settled.map { it.statusCode() }.sorted())
                assertEquals(1, settled.map { it.body() }.distinct().size); assertEquals(1, fake.inspect().size)
                fixture.owner { sql ->
                    sql.autoCommit = false
                    sql.exec("LOCK TABLE proposals.promotion IN ACCESS EXCLUSIVE MODE")
                    try { send(read(port, ref), 408, done) } finally { sql.rollback() }
                }
                send(read(port, ref), 200, done)
                assertEquals(1, fake.inspect().size)
            }
        }
    }

    @Test fun `opened disclosure rechecks current authority after GET replay and fresh completion SQL closes`() {
        val fake = fake()
        val stage = AtomicReference("")
        val reached = AtomicReference(CountDownLatch(0))
        val resume = AtomicReference(CountDownLatch(0))
        fun barrier(at: String) = { if (stage.compareAndSet(at, "")) { reached.get().countDown(); await(resume.get()) } }
        val revoked = AtomicBoolean(); val changedUid = AtomicBoolean(); val disclosureChecks = AtomicInteger()
        TeachActivationPostgres(registry.manifest, true, fake.fixtureId).use { db ->
            val fixture = checkNotNull(db.proposals)
            val imports = imports(db, fixture)
            val validation = assertNotNull(TeachProposalValidationService.mount(imports, fixture.validationStore()))
            val store = fixture.promotionStore(afterRead = barrier("get"), beforeOpenedReplay = barrier("replay"),
                afterFinalizeCommit = barrier("fresh"))
            val promotion = assertNotNull(TeachProductionPromotionService.mount(validation, registry, store, fake))
            netty(imports, pilot(db), revoked = revoked, validation = validation, promotion = promotion,
                onVerify = { token -> if (revoked.get() || changedUid.get()) {
                    assertEquals("fixture-token", token)
                    assertEquals(0, rows(fixture, "SELECT pid FROM pg_stat_activity WHERE usename LIKE 'promotion_%'").size)
                    disclosureChecks.incrementAndGet()
                } }, resolveUid = { uid -> if (changedUid.get()) "synthetic-other" else uid }) { port, done ->
                activate(port, done, books[0]); send(post(port, 1), 201, done); send(post(port, 2), 201, done)
                val (fresh, a) = list(port, done)
                validate(port, done, a.validationRef); validate(port, done, fresh.validationRef)
                val original = result(send(confirm(port, a.validationRef), 201, done))
                fun denyAfterSql(at: String, request: HttpRequest, deny: AtomicBoolean) {
                    reached.set(CountDownLatch(1)); resume.set(CountDownLatch(1)); stage.set(at)
                    val response = http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    try {
                        await(reached.get())
                        // Fresh completion is already committed, independent of the pending HTTP disclosure.
                        if (at == "fresh") assertEquals(2, audit(fixture).count { it[4] != null })
                        deny.set(true)
                        resume.get().countDown()
                        assertEquals("", checked(response.get(8, TimeUnit.SECONDS), 403)); done.await()
                    } finally { deny.set(false); resume.get().countDown() }
                }
                denyAfterSql("get", read(port, a.validationRef), revoked)
                denyAfterSql("replay", confirm(port, a.validationRef), changedUid)
                assertEquals(1, fake.lookups.get()); assertEquals(1, fake.creates.get())
                assertEquals(original, result(send(read(port, a.validationRef), 200, done)))
                assertEquals(original, result(send(confirm(port, a.validationRef), 200, done)))
                denyAfterSql("fresh", confirm(port, fresh.validationRef), revoked)
                val recovered = result(send(read(port, fresh.validationRef), 200, done))
                assertEquals("opened", recovered.kind)
                assertEquals(recovered, result(send(confirm(port, fresh.validationRef), 200, done)))
                assertEquals(3, disclosureChecks.get()); assertEquals(2, fake.lookups.get()); assertEquals(2, fake.creates.get())
            }
        }
    }

    @Test fun `timed out verifier retains one shared slot until actual exit without starting promotion provider`() {
        val fake = fake()
        val armed = AtomicBoolean(); val checks = AtomicInteger(); val probeChecks = AtomicInteger()
        val verifierEntered = CountDownLatch(1); val releaseVerifier = CountDownLatch(1); val verifierLeaving = CountDownLatch(1)
        val park = AtomicBoolean(); val parkCount = AtomicInteger()
        val readersEntered = CountDownLatch(3); val releaseReaders = CountDownLatch(1)
        TeachActivationPostgres(registry.manifest, true, fake.fixtureId).use { db ->
            val fixture = checkNotNull(db.proposals)
            val imports = imports(db, fixture)
            val validation = assertNotNull(TeachProposalValidationService.mount(imports, fixture.validationStore()))
            val store = fixture.promotionStore(afterRead = {
                if (park.get() && parkCount.incrementAndGet() <= 3) { readersEntered.countDown(); await(releaseReaders) }
            })
            val promotion = assertNotNull(TeachProductionPromotionService.mount(validation, registry, store, fake))
            netty(imports, pilot(db), validation = validation, promotion = promotion, onVerify = { token ->
                if (token == "other-token") probeChecks.incrementAndGet()
                if (token == "fixture-token" && armed.get() && checks.incrementAndGet() == 3) {
                    verifierEntered.countDown()
                    try { assertTrue(releaseVerifier.await(30, TimeUnit.SECONDS), "blocked verifier not released") }
                    finally { verifierLeaving.countDown() }
                }
            }) { port, done ->
                activate(port, done, books[0]); send(post(port, 1), 201, done)
                val ref = list(port, done).single().validationRef; validate(port, done, ref)
                armed.set(true)
                val timed = http.sendAsync(confirm(port, ref), HttpResponse.BodyHandlers.ofString())
                try {
                    await(verifierEntered)
                    assertEquals("", checked(timed.get(18, TimeUnit.SECONDS), 408)); done.await()
                    assertEquals(1L, verifierLeaving.count)
                    assertEquals(0, fake.lookups.get()); assertEquals(0, fake.creates.get())
                    assertNull(audit(fixture).single()[4])
                    park.set(true)
                    val readers = List(3) { http.sendAsync(read(port, ref), HttpResponse.BodyHandlers.ofString()) }
                    await(readersEntered)
                    // This request's auth worker actually ran. The 429 is the upload semaphore, not worker rejection.
                    send(get(port, token = "other-token"), 429, done)
                    assertEquals(1, probeChecks.get()); assertEquals(3, parkCount.get())
                    releaseVerifier.countDown(); await(verifierLeaving)
                    // Keep the other three permits occupied while observing the released fourth. The latch above
                    // runs immediately before the verifier returns; the worker's completion callback follows it.
                    val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
                    while (true) {
                        val probe = http.send(get(port, token = "other-token"), HttpResponse.BodyHandlers.ofString()); done.await()
                        if (probe.statusCode() == 200) break
                        assertEquals(429, probe.statusCode()); assertTrue(System.nanoTime() < until, "admission was not returned")
                    }
                    releaseReaders.countDown()
                    for (reader in readers) { assertEquals("pending", result(checked(reader.get(8, TimeUnit.SECONDS), 200)).kind); done.await() }
                    assertEquals(0, fake.lookups.get()); assertEquals(0, fake.creates.get())
                    assertEquals("opened", result(send(confirm(port, ref), 201, done)).kind)
                    assertEquals(1, fake.lookups.get()); assertEquals(1, fake.creates.get())
                } finally { releaseVerifier.countDown(); releaseReaders.countDown() }
            }
        }
    }

    @Test fun `current synthetic authority and fixture role ACLs reject without opening or publishing`() {
        val fake = fake()
        TeachActivationPostgres(registry.manifest, true, fake.fixtureId).use { db ->
            val fixture = checkNotNull(db.proposals)
            val imports = imports(db, fixture)
            val validation = assertNotNull(TeachProposalValidationService.mount(imports, fixture.validationStore()))
            val armed = AtomicBoolean(); val tokenChecks = AtomicInteger(); val received = AtomicInteger()
            val promotion = assertNotNull(TeachProductionPromotionService.mount(validation, registry, fixture.promotionStore(), fake))
            netty(imports, pilot(db), validation = validation, promotion = promotion, receives = received,
                resolveUid = { uid -> if (armed.get() && tokenChecks.incrementAndGet() == 3) "synthetic-other" else uid }) { port, done ->
                activate(port, done, books[0]); send(post(port, 1), 201, done)
                val a = list(port, done).single(); validate(port, done, a.validationRef)
                val before = received.get()
                send(confirm(port, a.validationRef, "invalid"), 401, done)
                send(confirm(port, a.validationRef, "not-nominated"), 403, done)
                send(read(port, a.validationRef), 200, done); assertEquals(before, received.get())
                send(confirm(port, absent), 404, done); send(confirm(port, a.validationRef, "other-token"), 404, done)
                send(confirm(port, a.validationRef, text = """{"validationRef":"${a.validationRef}","confirmPromotion":true,"revisionId":"override"}"""), 400, done)
                assertTrue(audit(fixture).isEmpty())
                fixture.promotionConnection(true).use { conn ->
                    conn.prepareStatement("INSERT INTO proposals.promotion (validation_ref, actor, digest) VALUES (?, ?, ?)").use {
                        it.setObject(1, UUID.fromString(a.validationRef)); it.setString(2, "synthetic-other"); it.setString(3, "a".repeat(64))
                        val failure = assertFailsWith<PSQLException> { it.executeUpdate() }
                        assertEquals("23503", failure.sqlState)
                        assertEquals("promotion_event_fk", failure.serverErrorMessage?.constraint)
                    }
                }
                assertTrue(audit(fixture).isEmpty())
                assertTrue(rows(fixture, "SELECT 1 FROM proposals.promotion").isEmpty())
                armed.set(true); send(confirm(port, a.validationRef), 403, done); armed.set(false)
                assertEquals("pending", result(send(read(port, a.validationRef), 200, done)).kind)
                assertEquals(0, fake.lookups.get()); assertTrue(fake.inspect().isEmpty())
            }
            netty(imports, validation = validation) { port, done -> send(read(port, absent), 404, done) }
            netty(imports, env = "prod", validation = validation, promotion = promotion) { port, done -> send(confirm(port, absent), 404, done) }
            fun denied(sql: String, write: Boolean) = fixture.promotionConnection(write).use { conn ->
                assertEquals("42501", assertFailsWith<SQLException> { conn.exec(sql) }.sqlState)
            }
            for (write in listOf(false, true)) {
                denied("SELECT * FROM proposals.proposal", write)
                denied("UPDATE proposals.validation SET validated_at = now()", write)
                denied("UPDATE proposals.batch SET revision = ''", write)
                denied("DELETE FROM proposals.promotion", write)
                denied("ALTER TABLE proposals.promotion DROP COLUMN digest", write)
                denied("CREATE TABLE public.extra (key integer)", write)
                denied("UPDATE proposals.promotion SET digest = ''", write)
                for (database in listOf(db.qaDb, db.sentinelDb, "postgres", "template1", "fixture_admin")) {
                    assertEquals("42501", assertFailsWith<SQLException> { fixture.promotionConnection(write, database).close() }.sqlState)
                }
            }
            denied("UPDATE proposals.promotion SET proposal_id = 17", false)
            denied("INSERT INTO proposals.promotion (validation_ref, actor, digest) VALUES ('$absent','synthetic-author','${"a".repeat(64)}')", false)
            for (conn in listOf(fixture.validationConnection(), fixture.connection(true), fixture.connection(false))) conn.use {
                assertEquals("42501", assertFailsWith<SQLException> { it.exec("SELECT op_key FROM proposals.promotion") }.sqlState)
            }
        }
    }
}
