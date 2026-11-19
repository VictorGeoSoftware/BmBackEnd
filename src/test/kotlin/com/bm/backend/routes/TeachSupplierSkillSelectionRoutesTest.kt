package com.bm.backend.routes

import com.bm.backend.configurePlugins
import com.bm.backend.models.TeachActiveSkill
import com.bm.backend.repositories.ports.TeachActiveSkillReader
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
import io.ktor.server.request.path
import io.ktor.server.routing.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.ProxySelector
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.security.SecureRandom
import java.sql.SQLException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class TeachSupplierSkillSelectionRoutesTest {
    private val books = activationWorkbooks().take(2)
    private val a = books[0]
    private val b = books[1]
    private val registry = TeachActivationRegistry(books.map { it.digest }.toSet())
    private val nominated = "author@example.invalid"
    private val notNominated = "not-nominated@example.invalid"
    private val path = "/api/v1$SUPPLIER_SELECTION_PATH"
    private val named = "bm-pilot-gesternova.xlsx"
    private val generic = "carga.xlsx"
    private val marker = supplierMarkerWorkbook(markerRow(1, " GeSTERNOVA "))
    private val http = HttpClient.newBuilder().proxy(ProxySelector.of(null)).build()

    private class CountedReader(private val reader: TeachActiveSkillReader) : TeachActiveSkillReader by reader {
        val lookups = AtomicInteger()
        val inFlight = AtomicInteger()
        override fun read(manifest: String, supplier: String, remainingMillis: () -> Long, checkpoint: () -> Unit): TeachActiveSkill? {
            lookups.incrementAndGet()
            inFlight.incrementAndGet()
            try { return reader.read(manifest, supplier, remainingMillis, checkpoint) }
            finally { inFlight.decrementAndGet() }
        }
    }

    private fun <T> netty(selection: TeachSupplierSkillSelectionService?, pilot: TeachSkillActivationService? = null,
                          environment: String = "qa", reads: AtomicInteger = AtomicInteger(),
                          admins: InMemoryAdminUsersRepository = admins(), priorModes: Boolean = true,
                          completion: TeachCallCompletion? = null, run: (Int) -> T): T {
        val server = embeddedServer(Netty, applicationEngineEnvironment {
            config = MapApplicationConfig()
            connector { host = "127.0.0.1"; port = 0 }
            module {
                configurePlugins() // Exercise the existing unmatched/non-QA no-store guard, not a bespoke test filter.
                completion?.install(this) { it.request.path() == path }
                receivePipeline.intercept(ApplicationReceivePipeline.Before) { reads.incrementAndGet() }
                routing { route("/api/v1") {
                    teachMeAccessRoutes(AdminAccessControlService(admins), TeachMeAccessPolicy(environment, nominated),
                        clarificationEnabled = priorModes, supplierDraftEnabled = priorModes, draftPreviewEnabled = priorModes,
                        clarificationAdapter = TeachClarificationAdapter { error("selection reached clarification adapter") },
                        supplierDraftAdapter = TeachSupplierDraftAdapter { _, _ -> error("selection reached draft adapter") },
                        activationPilot = pilot, supplierSelection = selection) { token ->
                        val email = when (token) {
                            "fixture-token" -> nominated
                            "not-admin" -> "not-admin@example.invalid"
                            "not-nominated" -> notNominated
                            else -> throw FirebaseAuthException(ErrorCode.UNAUTHENTICATED, "invalid synthetic token",
                                null, null, AuthErrorCode.INVALID_ID_TOKEN)
                        }
                        AuthenticatedFirebaseUser("synthetic-author", email, null, Instant.EPOCH, Instant.MAX)
                    }
                } }
            }
        })
        return try {
            server.start(wait = false)
            run(runBlocking { server.resolvedConnectors().single().port })
        } finally { server.stop(100, 1_000) }
    }

    private fun admins() = InMemoryAdminUsersRepository().apply { add(nominated); add(notNominated) }
    private fun wire(filename: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(filename.encodeToByteArray())
    private fun request(port: Int, bytes: ByteArray, route: String = path, type: String = WORKBOOK_MEDIA_TYPE,
                        filename: String? = named, headers: Map<String, String> = emptyMap()): HttpRequest =
        HttpRequest.newBuilder(URI("http://127.0.0.1:$port$route")).timeout(Duration.ofSeconds(18))
            .header("Authorization", "Bearer fixture-token").header("Content-Type", type)
            .apply {
                if (filename != null) header(WORKBOOK_FILENAME_HEADER, wire(filename))
                headers.forEach { (name, value) -> header(name, value) }
            }.POST(HttpRequest.BodyPublishers.ofByteArray(bytes)).build()

    private fun send(request: HttpRequest, status: Int): HttpResponse<String> =
        http.send(request, HttpResponse.BodyHandlers.ofString()).also { checkResponse(it, status) }

    private fun checkResponse(response: HttpResponse<*>, status: Int) {
        assertEquals(status, response.statusCode())
        assertEquals(listOf("no-store"), response.headers().allValues("Cache-Control"))
    }
    private fun HttpResponse<String>.json() = Json.parseToJsonElement(body()).jsonObject
    private fun error(port: Int, name: String, bytes: ByteArray, code: String, status: Int = 409, completion: TeachCallCompletion? = null) {
        assertEquals(buildJsonObject { put("error", code) }, send(request(port, bytes, filename = name), status).json())
        completion?.await()
    }
    private fun selected(port: Int, name: String, bytes: ByteArray, revision: String, generation: String,
                         source: String, headers: Map<String, String> = emptyMap(), completion: TeachCallCompletion? = null) {
        assertEquals(buildJsonObject {
            put("kind", "selected"); put("environment", "qa"); put("supplierKey", "gesternova")
            put("revisionId", revision); put("generation", generation); put("source", source)
        }, send(request(port, bytes, filename = name, headers = headers), 200).json())
        completion?.await()
    }

    private fun stage(port: Int, length: Int = 1000, route: String = path, auth: String = "Authorization: Bearer fixture-token\r\n",
                      type: String = WORKBOOK_MEDIA_TYPE, filename: String = "$WORKBOOK_FILENAME_HEADER: ${wire(named)}\r\n",
                      extra: String = ""): Socket = Socket().apply {
        try {
            soTimeout = 2_500
            connect(InetSocketAddress("127.0.0.1", port), 2_000)
            getOutputStream().write(("POST $route HTTP/1.1\r\nHost: 127.0.0.1\r\n${auth}Content-Type: $type\r\n" +
                "$filename${extra}Content-Length: $length\r\nConnection: close\r\n\r\n").encodeToByteArray())
        } catch (failure: Throwable) {
            close()
            throw failure
        }
    }

    private fun Socket.expectStatus(status: Int, completion: TeachCallCompletion? = null) {
        val headers = getInputStream().readBytes().decodeToString().substringBefore("\r\n\r\n").split("\r\n")
        assertTrue(headers.first().startsWith("HTTP/1.1 $status "), "Expected HTTP $status: ${headers.first()}")
        assertEquals(1, headers.count { it.equals("Cache-Control: no-store", ignoreCase = true) })
        completion?.await()
    }

    private fun confirmation(port: Int, workbook: TeachActivationWorkbook, previous: String?, generation: String): HttpRequest {
        val preview = send(request(port, workbook.preview(), "/api/v1/teach-me/clarifications", QA_PREVIEW_MEDIA_TYPE, null), 200).json()
        assertEquals("qaPilotPreview", preview.getValue("kind").jsonPrimitive.content)
        assertEquals(workbook.revision, preview.getValue("preview").jsonObject.getValue("revisionId").jsonPrimitive.content)
        val active = preview.getValue("active").jsonObject
        assertEquals(previous, active.getValue("revisionId").jsonPrimitive.contentOrNull)
        assertEquals(generation, active.getValue("generation").jsonPrimitive.content)
        val body = buildJsonObject {
            put("revisionId", workbook.revision); put("expectedRevisionId", active.getValue("revisionId"))
            put("expectedGeneration", generation); put("receipt", preview.getValue("receipt"))
        }
        return request(port, body.toString().encodeToByteArray(), "/api/v1/teach-me/activation", "application/json", null)
    }

    private fun activated(response: HttpResponse<String>, book: TeachActivationWorkbook, generation: String) {
        checkResponse(response, 200)
        assertEquals(buildJsonObject {
            put("kind", "activated")
            put("active", buildJsonObject { put("revisionId", book.revision); put("generation", generation) })
        }, response.json())
    }

    @Test fun `selection uses only the committed QA pointer before during and after real A to B activation and restart`() {
        TeachActivationPostgres(registry.manifest).use { db ->
            assertEquals(GESTERNOVA_PREVIEW_HASH, a.digest)
            val markerDigest = sha256Hex(MessageDigest.getInstance("SHA-256").digest(marker))
            assertTrue(books.none { it.digest == markerDigest })
            val sentinelPointer = "f".repeat(64)
            db.sentinel { sql ->
                db.seed(sql, sentinelPointer, 19)
                sql.exec("INSERT INTO pilot.audit VALUES ('sentinel-fixture', 'gesternova', '$sentinelPointer', NULL, 19, 'sentinel', 'prod', now(), 'activated')")
            }
            val sentinel = db.sentinel(db::snapshot)
            assertEquals("42501", assertFailsWith<SQLException> { db.qaConnection(db.sentinelDb).close() }.sqlState)
            assertEquals("42501", assertFailsWith<SQLException> { db.readerConnection(db.sentinelDb).close() }.sqlState)
            assertEquals("42501", assertFailsWith<SQLException> { db.readerConnection("postgres").close() }.sqlState)
            db.readerConnection().use { sql ->
                sql.exec("SELECT * FROM pilot.identity; SELECT * FROM pilot.active")
                for (statement in listOf("SELECT * FROM pilot.audit", "UPDATE pilot.identity SET manifest = 'x'",
                    "INSERT INTO pilot.active VALUES ('x', '$sentinelPointer', 1)", "UPDATE pilot.active SET generation = 9",
                    "DELETE FROM pilot.active", "TRUNCATE pilot.active", "ALTER TABLE pilot.active ADD COLUMN forbidden text",
                    "CREATE SCHEMA forbidden", "CREATE TABLE pilot.forbidden (a int)", "CREATE TEMP TABLE forbidden (a int)")) {
                    assertEquals("42501", assertFailsWith<SQLException> { sql.exec(statement) }.sqlState, statement.substringBefore(' '))
                }
            }

            val reader = CountedReader(db.reader())
            val selection = assertNotNull(TeachSupplierSkillSelectionService.mount(reader, registry))
            val afterWrites = CountDownLatch(1)
            val allowCommit = CountDownLatch(1)
            val store = db.store(beforeCommit = { staged -> if (staged.generation == "2") {
                assertEquals(TeachActiveSkill(b.revision, "2"), staged)
                afterWrites.countDown()
                check(allowCommit.await(8, TimeUnit.SECONDS)) { "Selector did not finish during the pre-commit barrier" }
            } })
            val pilot = assertNotNull(TeachSkillActivationService.mount(store, registry, Clock.systemUTC(), ByteArray(32).also(SecureRandom()::nextBytes)))
            val empty = db.qaOwner(db::snapshot)
            assertEquals(listOf(emptyList(), emptyList()), empty)
            val completion = TeachCallCompletion()
            netty(selection, pilot, completion = completion) { port ->
                fun failure(name: String, bytes: ByteArray, code: String, status: Int = 409) =
                    error(port, name, bytes, code, status, completion)
                fun success(name: String, bytes: ByteArray, revision: String, generation: String, source: String,
                            headers: Map<String, String> = emptyMap()) =
                    selected(port, name, bytes, revision, generation, source, headers, completion)
                failure(named, a.bytes, "no_active_skill")
                assertEquals(1, reader.lookups.get())
                assertEquals(empty, db.qaOwner(db::snapshot))
                activated(send(confirmation(port, a, null, "0"), 200), a, "1")
                val committedA = db.qaOwner(db::snapshot)
                assertEquals(listOf(GESTERNOVA_SUPPLIER, a.revision, "1"), committedA[0].single())
                assertEquals(listOf(a.revision, null, "1", "synthetic-author", "qa"), committedA[1].single().subList(2, 7))
                success(named, a.bytes, a.revision, "1", "filename",
                    mapOf("Forwarded" to "host=example.invalid", "X-Forwarded-Environment" to "prod"))
                success(generic, marker, a.revision, "1", "content")
                success(" BM-PILOT-GESTERNOVA-20TD .XLSX", marker, a.revision, "1", "filename")
                failure("bm-pilot-luz-fixture.xlsx", a.bytes, "no_active_skill")
                failure(generic, supplierMarkerWorkbook(markerRow(1, "luz-fixture")), "no_active_skill")
                assertEquals(6, reader.lookups.get())
                for (name in listOf(generic, "backup-bm-pilot-gesternova.xlsx", "bm-pilot-geſternova.xlsx")) failure(name, a.bytes, "unknown_supplier")
                failure(generic, supplierMarkerWorkbook(sheet = "Otra hoja"), "unknown_supplier")
                failure(named, supplierMarkerWorkbook(markerRow(1, "luz-fixture")), "conflicting_supplier")
                failure(named, supplierMarkerWorkbook(markerRow(1, "gesternova") + markerRow(2, "luz-fixture")), "ambiguous_supplier")
                for (book in listOf(supplierMarkerWorkbook(markerRow(1, "another")), supplierMarkerWorkbook(sheetAttributes = "state=\"hidden\""),
                    supplierMarkerWorkbook(markerRow(1, "gesternova", "hidden=\"1\"")),
                    supplierMarkerWorkbook("""<row r="1">${markerText("A1", SYNTHETIC_SUPPLIER_MARKER)}<c r="B1" t="str"><f>"gesternova"</f><v>gesternova</v></c></row>"""),
                    supplierMarkerWorkbook("""<row r="1">${markerText("A1", SYNTHETIC_SUPPLIER_MARKER)}</row>"""),
                    supplierMarkerWorkbook(markerRow(1, "gesternova") + markerRow(1, "gesternova")))) failure(named, book, "invalid_supplier_evidence")
                for (book in listOf(
                    supplierMarkerWorkbook(relationshipType = null), supplierMarkerWorkbook(relationshipTarget = null),
                    supplierMarkerWorkbook(sharedSupplierMarkerRow(), sharedStrings = malformedSupplierSharedStrings),
                    supplierMarkerWorkbook(sharedSupplierMarkerRow("1"), sharedStrings = validSupplierSharedStrings),
                )) failure(named, book, "invalid_supplier_evidence")
                for (book in listOf(
                    supplierMarkerWorkbook(sheet = "Otra hoja", relationshipTarget = null),
                    supplierMarkerWorkbook(sharedSupplierMarkerRow(ref = "C1"), sharedStrings = malformedSupplierSharedStrings),
                    supplierMarkerWorkbook(sharedSupplierMarkerRow("1", "C1"), sharedStrings = validSupplierSharedStrings),
                )) failure(named, book, "unsupported_workbook", 415)
                val unexpectedChild = "<note/>"
                val lookupsBeforeBindings = reader.lookups.get()
                for (book in listOf(supplierMarkerWorkbook(sheetBody = unexpectedChild),
                    supplierMarkerWorkbook(relationshipBody = unexpectedChild))) {
                    failure(named, book, "invalid_supplier_evidence")
                    assertEquals(lookupsBeforeBindings, reader.lookups.get())
                }
                for (book in listOf(supplierMarkerWorkbook(sheet = "Otra hoja", sheetBody = unexpectedChild),
                    supplierMarkerWorkbook(sheet = "Otra hoja", relationshipBody = unexpectedChild))) {
                    failure(named, book, "unsupported_workbook", 415)
                    assertEquals(lookupsBeforeBindings, reader.lookups.get())
                }
                failure(named, "invalid synthetic bytes".encodeToByteArray(), "unsupported_workbook", 415)
                failure(named, byteArrayOf(), "invalid_message", 400)
                failure(named, ByteArray(WORKBOOK_BODY_LIMIT + 1), "upload_too_large", 413)
                assertEquals(6, reader.lookups.get(), "Pre-catalog failures must not read the pointer")
                success(generic, supplierMarkerWorkbook(exactSize = WORKBOOK_BODY_LIMIT), a.revision, "1", "content")
                assertEquals(committedA, db.qaOwner(db::snapshot))

                val replace = confirmation(port, b, a.revision, "1")
                val pending = http.sendAsync(replace, HttpResponse.BodyHandlers.ofString())
                try {
                    assertTrue(afterWrites.await(5, TimeUnit.SECONDS), "Both activation SQL writes must precede the read")
                    assertFalse(pending.isDone)
                    success(generic, marker, a.revision, "1", "content")
                    assertEquals(committedA, db.qaOwner(db::snapshot))
                } finally { allowCommit.countDown() }
                activated(pending.get(6, TimeUnit.SECONDS), b, "2")
                success(named, a.bytes, b.revision, "2", "filename")
                assertEquals(9, reader.lookups.get())
            }
            val committedB = db.qaOwner(db::snapshot)
            assertEquals(listOf(GESTERNOVA_SUPPLIER, b.revision, "2"), committedB[0].single())
            assertEquals(2, committedB[1].size)
            assertEquals(listOf(a.revision, b.revision), committedB[1].map { it[2] })
            assertEquals(listOf(null, a.revision), committedB[1].map { it[3] })
            assertEquals(listOf("1", "2"), committedB[1].map { it[4] })
            assertTrue(committedB[1].all { it[6] == "qa" && it.last() == "activated" })
            val equivalent = TeachActivationRegistry(setOf(b.digest, a.digest))
            val restarted = assertNotNull(TeachSupplierSkillSelectionService.mount(db.reader(), equivalent))
            netty(restarted, priorModes = false) { port ->
                selected(port, generic, marker, b.revision, "2", "content")
                assertEquals(committedB, db.qaOwner(db::snapshot))
                db.qaOwner { it.exec("DELETE FROM pilot.identity") }
                error(port, named, a.bytes, "catalog_unavailable", 503)
                assertEquals(committedB, db.qaOwner(db::snapshot))
                db.qaOwner { it.exec("INSERT INTO pilot.identity VALUES ('${db.storeId}', '${registry.manifest}')") }
                db.qaOwner { it.exec("UPDATE pilot.active SET revision = '$sentinelPointer'") }
                val corrupt = db.qaOwner(db::snapshot)
                error(port, named, a.bytes, "catalog_unavailable", 503)
                assertEquals(corrupt, db.qaOwner(db::snapshot))
                assertNull(TeachSupplierSkillSelectionService.mount(db.reader(), equivalent))
                db.qaOwner { it.exec("UPDATE pilot.active SET revision = '${b.revision}'") }
            }
            assertEquals(committedB, db.qaOwner(db::snapshot))
            assertEquals(sentinel, db.sentinel(db::snapshot))
        }
    }

    @Test fun `mount and QA authorization fail closed before receiving a body or looking up a pointer`() {
        TeachActivationPostgres(registry.manifest).use { db ->
            val reads = AtomicInteger()
            val wrongManifest = TeachActivationRegistry(setOf(a.digest))
            val wrongDatabase = TeachSupplierSkillSelectionService.mount(db.reader(database = db.sentinelDb), registry)
            val wrongRole = TeachSupplierSkillSelectionService.mount(db.reader(role = db.qaRole), registry)
            val wrong = TeachSupplierSkillSelectionService.mount(db.reader(), wrongManifest)
            assertNull(wrongDatabase)
            assertNull(wrongRole)
            assertNull(wrong)
            TeachActivationPostgres(registry.manifest).use { other ->
                assertNotEquals(db.storeId, other.storeId)
                assertNull(TeachSupplierSkillSelectionService.mount(other.reader(id = db.storeId), registry))
                assertNotNull(TeachSupplierSkillSelectionService.mount(other.reader(), registry))
            }
            val counted = CountedReader(db.reader())
            val selection = assertNotNull(TeachSupplierSkillSelectionService.mount(counted, registry))
            val pilot = assertNotNull(TeachSkillActivationService.mount(db.store(), registry, Clock.systemUTC(), ByteArray(32).also(SecureRandom()::nextBytes)))
            val empty = db.qaOwner(db::snapshot)
            for ((capability, env) in listOf(null to "qa", wrong to "qa", wrongDatabase to "qa", wrongRole to "qa",
                selection to "prod", selection to "")) {
                netty(capability, pilot = pilot, environment = env, reads = reads) { port ->
                    stage(port, extra = "X-Teach-Environment: qa\r\n").use { it.expectStatus(404) }
                    stage(port, route = "/api/v1/teach-me/%73elect-supplier-skill/").use { it.expectStatus(404) }
                }
            }
            val admins = admins()
            val completion = TeachCallCompletion()
            netty(selection, reads = reads, admins = admins, completion = completion) { port ->
                for ((auth, status) in listOf("" to 401, "Authorization: Bearer invalid\r\n" to 401,
                    "Authorization: Bearer fixture-token\r\nAuthorization: Bearer fixture-token\r\n" to 401,
                    "Authorization: Bearer not-admin\r\n" to 403, "Authorization: Bearer not-nominated\r\n" to 403)) {
                    stage(port, auth = auth, extra = "X-Teach-Environment: prod\r\n").use { it.expectStatus(status, completion) }
                }
                for (extra in listOf("Content-Disposition: attachment\r\n", "X-Teach-Environment: prod\r\n", "X-BM-Env: prod\r\n",
                    "X-Teach-Supplier-Key: gesternova\r\n", "profileKey: synthetic\r\n", "X-Teach-Revision-Id: synthetic\r\n",
                    "X-Teach-Workbook-SHA256: synthetic\r\n", "X-Teach-Reference-Filename: synthetic\r\n")) {
                    stage(port, extra = extra).use { it.expectStatus(400, completion) }
                }
                val header = "$WORKBOOK_FILENAME_HEADER: ${wire(named)}\r\n"
                for (filename in listOf("", header + header, "$WORKBOOK_FILENAME_HEADER: ${wire(named)}=\r\n",
                    "$WORKBOOK_FILENAME_HEADER: _w\r\n", *listOf("../$named", "..\\$named", "\u202e$named", "\uFEFF$named", "bad\u0000.xlsx",
                        "a".repeat(251) + ".xlsx", "book.xls").map { "$WORKBOOK_FILENAME_HEADER: ${wire(it)}\r\n" }.toTypedArray())) {
                    stage(port, filename = filename).use { it.expectStatus(400, completion) }
                }
                stage(port, route = "$path?supplierKey=gesternova").use { it.expectStatus(400, completion) }
                stage(port, extra = "Content-Type: $WORKBOOK_MEDIA_TYPE\r\n").use { it.expectStatus(400, completion) }
                stage(port, type = "$WORKBOOK_MEDIA_TYPE; charset=utf-8").use { it.expectStatus(415, completion) }
                stage(port, extra = "Content-Encoding: identity\r\n").use { it.expectStatus(415, completion) }
                assertEquals(0, reads.get())
                assertEquals(0, counted.lookups.get())
                error(port, named, a.bytes, "no_active_skill", completion = completion)
                admins.remove(nominated)
                stage(port).use { it.expectStatus(403, completion) }
                assertEquals(1, reads.get())
                assertEquals(1, counted.lookups.get())
            }
            assertEquals(empty, db.qaOwner(db::snapshot))
        }
    }

    @Test fun `blocking reads share upload admission and release after deadline and peer disconnect`() {
        TeachActivationPostgres(registry.manifest).use { db ->
            val reader = CountedReader(db.reader())
            val selection = assertNotNull(TeachSupplierSkillSelectionService.mount(reader, registry))
            fun blockedReaders(): Int = db.qaOwner { sql ->
                sql.createStatement().use { it.executeQuery("SELECT count(*) FROM pg_stat_activity WHERE usename = '${db.readerRole}' AND wait_event_type = 'Lock'")
                    .use { assertTrue(it.next()); it.getInt(1) } }
            }
            fun await(condition: () -> Boolean) {
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4)
                while (!condition()) {
                    assertTrue(System.nanoTime() < deadline, "Timed out waiting for fixture state")
                    Thread.sleep(10)
                }
            }
            val reads = AtomicInteger()
            val initial = db.qaOwner(db::snapshot)
            netty(selection, reads = reads) { port ->
                db.qaOwner { locker ->
                    locker.autoCommit = false
                    locker.exec("LOCK TABLE pilot.active IN ACCESS EXCLUSIVE MODE")
                    val held = mutableListOf<Socket>()
                    try {
                        val start = System.nanoTime()
                        val timed = stage(port, a.bytes.size).also { held += it; it.soTimeout = 17_000 }
                        await { reads.get() == 1 }
                        Thread.sleep(2_000) // The body wait must consume the same deadline as the JDBC lock wait.
                        repeat(3) {
                            val socket = stage(port, a.bytes.size)
                            held += socket
                            socket.getOutputStream().write(a.bytes)
                        }
                        timed.getOutputStream().write(a.bytes)
                        await { blockedReaders() == 4 }
                        assertEquals(4, reader.inFlight.get())
                        stage(port).use { it.expectStatus(429) }
                        stage(port, route = "/api/v1/teach-me/messages").use { it.expectStatus(429) }
                        assertEquals(4, reads.get())
                        held.drop(1).forEach(Socket::close)
                        await { blockedReaders() == 1 && reader.inFlight.get() == 1 }
                        timed.expectStatus(408)
                        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) in 13_500..16_850)
                        await { blockedReaders() == 0 && reader.inFlight.get() == 0 }
                    } finally {
                        held.forEach(Socket::close)
                        locker.rollback()
                    }
                }
                error(port, named, a.bytes, "no_active_skill")
                assertEquals(5, reader.lookups.get())
            }
            assertEquals(initial, db.qaOwner(db::snapshot))
        }
    }
}
