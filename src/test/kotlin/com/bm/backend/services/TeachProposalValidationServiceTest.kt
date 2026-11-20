package com.bm.backend.services

import com.bm.backend.models.*
import com.bm.backend.repositories.ports.TeachActiveSkillReader
import com.bm.backend.repositories.ports.TeachProposalImportStore
import com.bm.backend.repositories.ports.TeachProposalStoreUnavailable
import com.bm.backend.repositories.ports.TeachProposalValidationStore
import com.bm.backend.routes.InvalidTeachUpload
import com.bm.backend.routes.TeachUploadTooLarge
import com.bm.backend.routes.parseTeachProposalValidation
import com.bm.backend.routes.readTeachProposalValidation
import com.bm.backend.testing.activationWorkbooks
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.runBlocking
import java.util.UUID
import kotlin.test.*

class TeachProposalValidationServiceTest {
    private val ref = UUID.fromString("00000000-0000-4000-8000-000000000001")
    private val actor = "synthetic-author"

    @Test fun `mount requires matching verified capabilities and confirmation never consults active rules or import writes`() {
        val registry = TeachActivationRegistry(activationWorkbooks().map { it.digest }.toSet())
        val selector = assertNotNull(TeachSupplierSkillSelectionService.mount(object : TeachActiveSkillReader {
            override val storeId = "a".repeat(32)
            override fun verify(manifest: String): Map<String, TeachActiveSkill> = emptyMap()
            override fun read(manifest: String, supplier: String, remainingMillis: () -> Long, checkpoint: () -> Unit): TeachActiveSkill? =
                error("Validation read a catalog pointer")
        }, registry))
        val descriptor = TeachProposalImportIdentity("b".repeat(32), selector.catalogId, registry.manifest, schemaVersion = 2)
        var verifyProjection = 0
        var verifyWrite = 0
        var calls = 0
        fun importPort(projectionOff: Boolean = false) = object : TeachProposalImportStore {
            override val identity = descriptor
            override fun verify() = Unit
            override fun verifyValidationProjection() { verifyProjection++; if (projectionOff) throw TeachProposalStoreUnavailable() }
            override fun exists(attempt: TeachProposalImportAttempt, remaining: () -> Long, checkpoint: () -> Unit): Boolean = error("Probe import")
            override fun record(attempt: TeachProposalImportAttempt, pending: TeachProposalImportPending?, remaining: () -> Long,
                                checkpoint: () -> Unit): TeachProposalImportReceipt = error("Write import")
            override fun list(actor: String, before: Long?, remaining: () -> Long, checkpoint: () -> Unit): TeachProposalImportPage = error("List import")
        }
        val imports = assertNotNull(TeachProposalImportService.mount(selector, importPort()))
        val result = TeachProposalValidationResult("alreadyValidated", "5", "f".repeat(64), "1", "2026-03-01T10:11:12Z")
        fun store(id: TeachProposalImportIdentity = descriptor, fail: Boolean = false) = object : TeachProposalValidationStore {
            override val identity = id
            override fun verify() { verifyWrite++; if (fail) throw TeachProposalStoreUnavailable() }
            override fun confirm(actor: String, ref: UUID, remaining: () -> Long, checkpoint: () -> Unit): TeachProposalValidationResult? {
                calls++; checkpoint()
                assertEquals(999L, remaining())
                return result.takeIf { actor == this@TeachProposalValidationServiceTest.actor && ref == this@TeachProposalValidationServiceTest.ref }
            }
        }
        assertNull(TeachProposalValidationService.mount(imports, store(descriptor.copy(storeId = "c".repeat(32)))))
        assertEquals(0, verifyProjection)
        assertEquals(0, verifyWrite)
        assertNull(TeachProposalValidationService.mount(assertNotNull(TeachProposalImportService.mount(selector, importPort(true))), store()))
        assertEquals(0, verifyWrite)
        assertNull(TeachProposalValidationService.mount(imports, store(fail = true)))
        val validation = assertNotNull(TeachProposalValidationService.mount(imports, store()))
        assertEquals(3, verifyProjection)
        assertEquals(2, verifyWrite)
        assertTrue(validation.isBoundTo(imports))
        assertFalse(validation.isBoundTo(TeachProposalImportService.mount(selector, importPort())))
        assertEquals(result, validation.confirm(actor, TeachProposalValidationConfirmation(ref), { 999 }) {})
        assertNull(validation.confirm("synthetic-other", TeachProposalValidationConfirmation(ref), { 999 }) {})
        assertEquals(2, calls)
    }

    @Test fun `bounded confirmation accepts explicit true and rejects non canonical or duplicate JSON members`() = runBlocking {
        val valid = """{"confirmExpectedPrices":true,"validationRef":"$ref"}"""
        assertEquals(ref, parseTeachProposalValidation(valid).ref)
        assertEquals(ref, parseTeachProposalValidation(valid.replace("validationRef", "validationRe\\u0066")).ref)
        for (text in listOf(valid.replace("true", "false"), valid.replace("true", "\"true\""), valid + " null",
            """{"validationRef":"$ref","validationRe\u0066":"$ref"}""",
            """{"confirmExpectedPrices":true,"confirmExpectedPrice\u0073":true}""", valid.replace("$ref", "0-0-0-0-1"))) {
            assertFailsWith<InvalidTeachUpload> { parseTeachProposalValidation(text) }
        }
        val limit = valid.padEnd(1024, ' ').encodeToByteArray()
        assertEquals(ref, readTeachProposalValidation(ByteReadChannel(limit), 1024) {}.ref)
        assertFailsWith<TeachUploadTooLarge> { readTeachProposalValidation(ByteReadChannel(limit + 32.toByte()), 1024) {} }
        assertFailsWith<InvalidTeachUpload> { readTeachProposalValidation(ByteReadChannel(byteArrayOf(0xc3.toByte(), 0x28)), 2) {} }
        assertFailsWith<InvalidTeachUpload> { readTeachProposalValidation(ByteReadChannel(limit), 1023) {} }
        Unit
    }
}
