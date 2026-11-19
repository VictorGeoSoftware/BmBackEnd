package com.bm.backend.routes

import com.bm.backend.models.ErrorResponse
import com.bm.backend.models.TeachClarificationResult
import com.bm.backend.models.TeachDraftPreviewResult
import com.bm.backend.models.TeachReferenceAttachment
import com.bm.backend.models.TeachReferenceReceipt
import com.bm.backend.models.TeachSupplierDraftResponse
import com.bm.backend.models.TeachWorkbookAttachment
import com.bm.backend.models.TeachWorkbookAuthDecision
import com.bm.backend.models.TeachWorkbookPrincipal
import com.bm.backend.models.TeachQaPilotPreview
import com.bm.backend.models.TeachActivationResult
import com.bm.backend.models.TeachWorkbookReceipt
import com.bm.backend.repositories.ports.TeachSkillCatalogUnavailable
import com.bm.backend.services.InvalidWorkbook
import com.bm.backend.services.InvalidReference
import com.bm.backend.services.InvalidTeachSupplierDraft
import com.bm.backend.services.TeachClarificationAdapter
import com.bm.backend.services.TeachClarificationConflict
import com.bm.backend.services.TeachClarificationService
import com.bm.backend.services.TeachDraftPreviewConflict
import com.bm.backend.services.TeachDraftPreviewService
import com.bm.backend.services.TeachReferenceValidator
import com.bm.backend.services.TeachSupplierDraftAdapter
import com.bm.backend.services.TeachSupplierDraftConflict
import com.bm.backend.services.TeachSupplierDraftService
import com.bm.backend.services.TeachSupplierSelectionConflict
import com.bm.backend.services.TeachSupplierSkillSelectionService
import com.bm.backend.services.TeachWorkbookAuthWorkers
import com.bm.backend.services.TeachWorkbookInspector
import com.bm.backend.services.TeachWorkbookValidator
import com.bm.backend.services.WorkbookBudgetExceeded
import com.bm.backend.services.TeachSkillActivationService
import com.bm.backend.services.TeachActivationUnavailable
import com.bm.backend.services.TeachActivationConflict
import com.bm.backend.services.teachSyntheticActor
import com.bm.backend.services.fixedTeachClarificationAdapter
import com.bm.backend.services.fixedTeachSupplierDraftAdapter
import com.bm.backend.services.isWorkbookBidi
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLDecodeException
import io.ktor.http.decodeURLPart
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.call
import io.ktor.server.netty.NettyApplicationCall
import io.ktor.server.request.receiveChannel
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.uri
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingApplicationCall
import io.ktor.server.routing.application
import io.ktor.server.routing.post
import io.ktor.utils.io.cancel
import io.netty.channel.ChannelFutureListener
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.util.Base64
import java.util.Locale
import java.util.concurrent.Semaphore
import kotlin.time.Duration.Companion.nanoseconds

internal const val WORKBOOK_MEDIA_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
internal const val WORKBOOK_FILENAME_HEADER = "X-Teach-Workbook-Filename"
internal const val WORKBOOK_BODY_LIMIT = 5 * 1024 * 1024
private const val WORKBOOK_MESSAGE_PATH = "/teach-me/messages"
private const val REFERENCE_PATH = "/teach-me/references"
private const val WORKBOOK_INSPECTION_PATH = "/teach-me/inspect-workbook"
private const val CLARIFICATION_PATH = "/teach-me/clarifications"
internal const val SUPPLIER_SELECTION_PATH = "/teach-me/select-supplier-skill"
private val teachUploadPaths = listOf(WORKBOOK_MESSAGE_PATH, REFERENCE_PATH, WORKBOOK_INSPECTION_PATH, CLARIFICATION_PATH)
private val teachUploadApiSegments = teachUploadPaths.map { ("/api/v1$it").drop(1).split('/') }.toSet()
private val activationApiSegments = ("/api/v1$ACTIVATION_PATH").drop(1).split('/')
private val selectionApiSegments = ("/api/v1$SUPPLIER_SELECTION_PATH").drop(1).split('/')
internal const val REFERENCE_FILENAME_HEADER = "X-Teach-Reference-Filename"
internal const val REFERENCE_BODY_LIMIT = 2 * 1024 * 1024
private val filenameWire = Regex("[A-Za-z0-9_-]{1,340}")
internal class InvalidTeachUpload : RuntimeException()
private val clarificationJson = Json { classDiscriminator = "kind" }

internal fun ApplicationCall.isTeachUpload(): Boolean {
    val segments = try {
        // As in Ktor 2.3 routing: skip empty segments and decode each segment, not the whole path.
        // Also keep no-store for trailing-slash variants that routing may leave unmatched.
        // All upload paths have four segments; one extra is enough to rule out a match.
        request.path().splitToSequence('/').filter(String::isNotEmpty).take(5).map { it.decodeURLPart() }.toList()
    } catch (_: URLDecodeException) {
        return false // Leave malformed request paths to Ktor.
    }
    return segments == activationApiSegments || segments == selectionApiSegments ||
        (request.httpMethod == HttpMethod.Post && segments in teachUploadApiSegments)
}

internal fun Route.teachWorkbookMessageRoutes(
    validator: TeachWorkbookValidator = TeachWorkbookValidator(),
    timeoutMillis: Long = 15_000,
    beforeUpload: suspend () -> Unit = {},
    clarificationEnabled: Boolean = false,
    clarificationAdapter: TeachClarificationAdapter = fixedTeachClarificationAdapter,
    supplierDraftEnabled: Boolean = false,
    supplierDraftAdapter: TeachSupplierDraftAdapter = fixedTeachSupplierDraftAdapter,
    draftPreviewEnabled: Boolean = false,
    activationPilot: TeachSkillActivationService? = null,
    supplierSelection: TeachSupplierSkillSelectionService? = null,
    authorize: (String) -> TeachWorkbookAuthDecision,
) {
    // All POST routes use this one set of workers and permits; GET is independent.
    val authWorkers = TeachWorkbookAuthWorkers(authorize)
    val owner = application
    val monitor = owner.environment.monitor
    monitor.subscribe(ApplicationStopped, object : (Application) -> Unit {
        override fun invoke(stopped: Application) {
            if (stopped !== owner) return
            monitor.unsubscribe(ApplicationStopped, this)
            authWorkers.close()
        }
    })
    val uploadPermits = Semaphore(4)
    val referenceValidator = TeachReferenceValidator()
    val inspector = TeachWorkbookInspector()
    val clarification = if (clarificationEnabled) TeachClarificationService(validator, inspector, referenceValidator, clarificationAdapter) else null
    val supplierDraft = if (clarificationEnabled && supplierDraftEnabled) TeachSupplierDraftService(validator, inspector, supplierDraftAdapter) else null
    val draftPreview = if (supplierDraft != null && draftPreviewEnabled) TeachDraftPreviewService(validator, inspector) else null
    val pilot = activationPilot.takeIf { draftPreview != null }
    // At most the same four admitted upload requests can dispatch blocking catalog work.
    val catalogDispatcher = Dispatchers.IO.limitedParallelism(4)
    val paths = teachUploadPaths + listOfNotNull(ACTIVATION_PATH.takeIf { pilot != null },
        SUPPLIER_SELECTION_PATH.takeIf { supplierSelection != null })
    for (path in paths.filter { it != CLARIFICATION_PATH || clarification != null }) post(path) {
        if (call.response.headers[HttpHeaders.CacheControl] == null) call.response.header(HttpHeaders.CacheControl, "no-store")
        // A direct HTTP/1 caller should not reuse the connection after an early rejection.
        if (call.request.local.version.startsWith("HTTP/1")) call.response.header(HttpHeaders.Connection, "close")

        if (path == CLARIFICATION_PATH && supplierDraft == null && SUPPLIER_DRAFT_MEDIA_TYPE.equals(
                call.request.headers.getAll(HttpHeaders.ContentType)?.singleOrNull(), ignoreCase = true)) {
            call.respond(HttpStatusCode.NotFound)
            return@post
        }
        if (path == CLARIFICATION_PATH && draftPreview == null && DRAFT_PREVIEW_MEDIA_TYPE.equals(
                call.request.headers.getAll(HttpHeaders.ContentType)?.singleOrNull(), ignoreCase = true)) {
            call.respond(HttpStatusCode.NotFound)
            return@post
        }
        val pilotPreview = path == CLARIFICATION_PATH && QA_PREVIEW_MEDIA_TYPE.equals(
            call.request.headers.getAll(HttpHeaders.ContentType)?.singleOrNull(), ignoreCase = true)
        val activate = path == ACTIVATION_PATH
        val select = path == SUPPLIER_SELECTION_PATH
        if (pilotPreview && pilot == null) {
            call.respond(HttpStatusCode.NotFound)
            return@post
        }

        val authHeader = call.request.headers[HttpHeaders.Authorization]
        if (authHeader.isNullOrBlank() || !authHeader.startsWith("Bearer ") ||
            ((path == CLARIFICATION_PATH || activate || select) && (call.request.headers.getAll(HttpHeaders.Authorization)?.size != 1 || ',' in authHeader))) {
            call.respond(HttpStatusCode.Unauthorized, ErrorResponse(message = "Missing or invalid Authorization header"))
            return@post
        }
        val token = authHeader.removePrefix("Bearer ").trim()
        if (token.isBlank()) {
            call.respond(HttpStatusCode.Unauthorized, ErrorResponse(message = "Missing Firebase ID token"))
            return@post
        }
        call.withWorkbookPeer { checkPeer ->
            call.receiveTeachUpload(token, authWorkers, uploadPermits, validator, referenceValidator,
                path == REFERENCE_PATH, inspector, path == WORKBOOK_INSPECTION_PATH,
                clarification.takeIf { path == CLARIFICATION_PATH }, supplierDraft.takeIf { path == CLARIFICATION_PATH },
                draftPreview.takeIf { path == CLARIFICATION_PATH }, pilot.takeIf { activate || pilotPreview }, activate,
                supplierSelection.takeIf { select }, catalogDispatcher, beforeUpload, timeoutMillis, checkPeer)
        }
    }
}

private suspend fun ApplicationCall.withWorkbookPeer(block: suspend (() -> Unit) -> Unit) {
    val engineCall = (this as? RoutingApplicationCall)?.engineCall ?: this
    val channel = (engineCall as? NettyApplicationCall)?.context?.channel()
    if (channel == null) {
        block {} // The in-memory Ktor test host has no transport channel.
        return
    }
    try {
        coroutineScope {
            val job = coroutineContext.job
            val closed = channel.closeFuture()
            val listener = ChannelFutureListener { job.cancel(WorkbookPeerClosed()) }
            val checkPeer = { if (!channel.isActive) throw WorkbookPeerClosed() }
            closed.addListener(listener)
            try {
                checkPeer()
                block(checkPeer)
            } finally {
                closed.removeListener(listener)
            }
        }
    } catch (failure: CancellationException) {
        // Netty owns the closed connection; no application response for a known peer close.
        if (channel.isActive) throw failure
    }
}

private class WorkbookPeerClosed : CancellationException("Workbook upload peer disconnected")

private suspend fun ApplicationCall.receiveTeachUpload(
    token: String,
    authWorkers: TeachWorkbookAuthWorkers,
    uploadPermits: Semaphore,
    validator: TeachWorkbookValidator,
    referenceValidator: TeachReferenceValidator,
    reference: Boolean,
    inspector: TeachWorkbookInspector,
    inspectWorkbook: Boolean,
    clarification: TeachClarificationService?,
    supplierDraft: TeachSupplierDraftService?,
    draftPreview: TeachDraftPreviewService?,
    pilot: TeachSkillActivationService?,
    activate: Boolean,
    selection: TeachSupplierSkillSelectionService?,
    catalogDispatcher: kotlinx.coroutines.CoroutineDispatcher,
    beforeUpload: suspend () -> Unit,
    timeoutMillis: Long,
    checkPeer: () -> Unit,
) {
    val admitted = System.nanoTime()
    fun remaining() = timeoutMillis * 1_000_000 - (System.nanoTime() - admitted)
    fun remainingMillis() = (remaining() / 1_000_000).also { if (it <= 0) throw WorkbookDeadline() }
    val decision = authWorkers.submit(token)
    if (decision == null) {
        checkPeer()
        respond(HttpStatusCode.TooManyRequests)
        return
    }

    var acquiredUpload = false
    try {
        val outcome = withTimeoutOrNull(remaining().nanoseconds) {
            val context = currentCoroutineContext()
            val checkpoint = {
                context.ensureActive()
                checkPeer()
                if (remaining() <= 0) throw WorkbookDeadline()
            }
            val resolved = decision.await()
            checkpoint()
            val authorization = resolved.getOrThrow()
            if (authorization !is TeachWorkbookPrincipal && authorization != TeachWorkbookAuthDecision.Allowed) {
                return@withTimeoutOrNull authorization
            }
            if (pilot != null && (authorization !is TeachWorkbookPrincipal || !teachSyntheticActor.matches(authorization.uid))) {
                return@withTimeoutOrNull HttpStatusCode.Forbidden
            }
            beforeUpload()
            checkpoint()
            acquiredUpload = uploadPermits.tryAcquire()
            if (!acquiredUpload) {
                return@withTimeoutOrNull HttpStatusCode.TooManyRequests
            }
            val types = request.headers.getAll(HttpHeaders.ContentType)
            if ('?' in request.uri || (types != null && (types.size != 1 || ',' in types.single()))) {
                throw InvalidTeachUpload()
            }
            val type = types?.singleOrNull()
            if (selection != null && request.headers.names().any(::isSelectionOverride)) throw InvalidTeachUpload()
            if (pilot != null) {
                val uid = (authorization as TeachWorkbookPrincipal).uid
                val catalogRemaining = { checkpoint(); remainingMillis() }
                if (!type.equals(if (activate) "application/json" else QA_PREVIEW_MEDIA_TYPE, ignoreCase = true) ||
                    request.headers.getAll(HttpHeaders.ContentEncoding) != null) return@withTimeoutOrNull HttpStatusCode.UnsupportedMediaType
                if (listOf(WORKBOOK_FILENAME_HEADER, REFERENCE_FILENAME_HEADER, HttpHeaders.ContentDisposition)
                        .any { request.headers.getAll(it) != null }) throw InvalidTeachUpload()
                checkpoint()
                val channel = receiveChannel()
                val json = if (activate) {
                    val parsed = try { readTeachActivation(channel, checkpoint) } finally { channel.cancel() }
                    val claim = pilot.verify(parsed, uid)
                    checkpoint()
                    val recheck = authWorkers.submit(token) ?: return@withTimeoutOrNull HttpStatusCode.TooManyRequests
                    val same = try { recheck.await().getOrThrow() } finally { recheck.cancel() }
                    checkpoint()
                    if (same != authorization) return@withTimeoutOrNull when (same) {
                        TeachWorkbookAuthDecision.InvalidToken -> HttpStatusCode.Unauthorized
                        else -> HttpStatusCode.Forbidden
                    }
                    val result = withContext(catalogDispatcher) { pilot.activate(claim, catalogRemaining, checkpoint) }
                    clarificationJson.encodeToString(TeachActivationResult.serializer(), result)
                } else {
                    val frame = try { readDraftPreviewFrame(channel, true, checkpoint) } finally { channel.cancel() }
                    val result = withContext(catalogDispatcher) { pilot.preview(checkNotNull(draftPreview), frame, uid, catalogRemaining, checkpoint) }
                    clarificationJson.encodeToString(TeachQaPilotPreview.serializer(), result)
                }
                val bytes = json.encodeToByteArray()
                checkpoint()
                if (bytes.size > 4096) return@withTimeoutOrNull HttpStatusCode.InternalServerError
                respondBytes(bytes, ContentType.parse("application/json; charset=UTF-8"), HttpStatusCode.OK)
                return@withTimeoutOrNull Unit
            }
            if (clarification != null) {
                val draftWire = SUPPLIER_DRAFT_MEDIA_TYPE.equals(type, ignoreCase = true)
                val previewWire = DRAFT_PREVIEW_MEDIA_TYPE.equals(type, ignoreCase = true)
                if ((!draftWire && !previewWire && !CLARIFICATION_MEDIA_TYPE.equals(type, ignoreCase = true)) || request.headers.getAll(HttpHeaders.ContentEncoding) != null) {
                    return@withTimeoutOrNull HttpStatusCode.UnsupportedMediaType
                }
                if (previewWire) {
                    val service = checkNotNull(draftPreview)
                    if (request.headers.getAll(WORKBOOK_FILENAME_HEADER) != null || request.headers.getAll(REFERENCE_FILENAME_HEADER) != null) {
                        throw InvalidTeachUpload()
                    }
                    checkpoint()
                    val channel = receiveChannel()
                    val frame = try { readDraftPreviewFrame(channel, checkpoint) } finally { channel.cancel() }
                    val result = service.execute(frame, checkpoint)
                    checkpoint()
                    val json = clarificationJson.encodeToString(TeachDraftPreviewResult.serializer(), result).encodeToByteArray()
                    checkpoint()
                    return@withTimeoutOrNull if (json.size <= 4096) json else HttpStatusCode.InternalServerError
                }
                if (draftWire) {
                    val service = checkNotNull(supplierDraft)
                    if (request.headers.getAll(WORKBOOK_FILENAME_HEADER) != null || request.headers.getAll(REFERENCE_FILENAME_HEADER) != null) {
                        throw InvalidTeachUpload()
                    }
                    checkpoint()
                    val channel = receiveChannel()
                    val frame = try { readSupplierDraftFrame(channel, checkpoint) } finally { channel.cancel() }
                    val json = if (frame.choice == null) {
                        clarificationJson.encodeToString(TeachClarificationResult.serializer(), service.question(frame, checkpoint))
                    } else {
                        clarificationJson.encodeToString(TeachSupplierDraftResponse.serializer(), TeachSupplierDraftResponse("draft", service.draft(frame, checkpoint)))
                    }
                    return@withTimeoutOrNull json.encodeToByteArray().also { check(it.size <= 4096); checkpoint() }
                }
                checkpoint()
                val channel = receiveChannel()
                val frame = try { readClarificationFrame(channel, checkpoint) } finally { channel.cancel() }
                val result = clarification.clarify(frame, checkpoint)
                checkpoint()
                return@withTimeoutOrNull clarificationJson.encodeToString(TeachClarificationResult.serializer(), result)
                    .encodeToByteArray().also { check(it.size <= 4096); checkpoint() }
            }
            val encoded = request.headers.getAll(if (reference) REFERENCE_FILENAME_HEADER else WORKBOOK_FILENAME_HEADER)
            val filename: String
            val referenceType = if (reference) type?.lowercase() else null
            val pdf = referenceType == "application/pdf"
            if (reference) {
                // Validate the singleton/name before media-type checks, as with the workbook route.
                filename = decodeFilename(encoded)
                if (!pdf && referenceType != "image/png") throw InvalidReference()
                validateExtension(filename, if (pdf) ".pdf" else ".png")
                if (request.headers.getAll(HttpHeaders.ContentEncoding) != null) throw InvalidReference()
            } else {
                filename = decodeFilename(encoded)
                validateExtension(filename, ".xlsx")
                if (!WORKBOOK_MEDIA_TYPE.equals(type, ignoreCase = true) ||
                    request.headers.getAll(HttpHeaders.ContentEncoding) != null) throw InvalidWorkbook()
            }
            checkpoint()
            val channel = receiveChannel()
            var size = 0
            val bytes = try {
                // The receive pipeline can suspend; check again before reserving upload memory.
                checkpoint()
                val buffer = ByteArray(if (reference) REFERENCE_BODY_LIMIT else WORKBOOK_BODY_LIMIT)
                while (size < buffer.size) {
                    checkpoint()
                    val read = channel.readAvailable(buffer, size, minOf(8192, buffer.size - size))
                    if (read < 0) break
                    size += read
                }
                checkpoint()
                if (size == buffer.size) {
                    val excess = channel.readAvailable(ByteArray(1), 0, 1)
                    checkpoint()
                    if (excess >= 0) throw TeachUploadTooLarge()
                }
                if (size == 0) throw InvalidTeachUpload()
                buffer
            } finally { channel.cancel() }
            if (selection != null) {
                val result = withContext(catalogDispatcher) {
                    selection.select(filename, validator, inspector, bytes, size, { checkpoint(); remainingMillis() }, checkpoint)
                }
                checkpoint()
                buildJsonObject {
                    put("kind", "selected")
                    put("environment", "qa")
                    put("supplierKey", result.revision.supplierKey)
                    put("revisionId", result.revision.revisionId)
                    put("generation", result.generation)
                    put("source", result.source.wire)
                }.toString().encodeToByteArray()
            } else if (reference) {
                referenceValidator.validate(bytes, size, pdf, checkpoint)
                checkpoint()
                TeachReferenceReceipt(TeachReferenceAttachment(if (pdf) "reference_pdf" else "reference_png", filename, size))
            } else if (inspectWorkbook) {
                inspector.inspect(validator, bytes, size, filename, checkpoint).also { checkpoint() }
            } else {
                validator.validate(bytes, size, checkpoint)
                checkpoint()
                TeachWorkbookReceipt(TeachWorkbookAttachment("workbook", filename, size))
            }
        }
        currentCoroutineContext().ensureActive()
        checkPeer()
        when {
            outcome == Unit || response.isCommitted -> Unit
            outcome == null || remaining() <= 0 -> respond(HttpStatusCode.RequestTimeout)
            outcome is TeachWorkbookAuthDecision -> denyWorkbookUpload(outcome)
            outcome is HttpStatusCode -> respond(outcome)
            outcome is TeachWorkbookReceipt -> respond(HttpStatusCode.OK, outcome)
            outcome is TeachReferenceReceipt -> respond(HttpStatusCode.OK, outcome)
            outcome is ByteArray -> respondBytes(outcome, ContentType.parse("application/json; charset=UTF-8"), HttpStatusCode.OK)
            else -> error("Unexpected Enséñame upload outcome")
        }
    } catch (_: WorkbookDeadline) {
        checkPeer()
        respond(HttpStatusCode.RequestTimeout)
    } catch (failure: TeachSupplierSelectionConflict) {
        checkPeer()
        if (remaining() <= 0) respond(HttpStatusCode.RequestTimeout)
        else respond(HttpStatusCode.Conflict, mapOf("error" to failure.error.wire))
    } catch (_: TeachSkillCatalogUnavailable) {
        checkPeer()
        if (remaining() <= 0) respond(HttpStatusCode.RequestTimeout)
        else respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "catalog_unavailable"))
    } catch (_: TeachActivationConflict) {
        checkPeer()
        respond(if (remaining() <= 0) HttpStatusCode.RequestTimeout else HttpStatusCode.Conflict)
    } catch (_: TeachActivationUnavailable) {
        checkPeer()
        respond(if (remaining() <= 0) HttpStatusCode.RequestTimeout else HttpStatusCode.ServiceUnavailable)
    } catch (_: TeachClarificationConflict) {
        checkPeer()
        respond(HttpStatusCode.Conflict)
    } catch (_: TeachSupplierDraftConflict) {
        checkPeer()
        respond(HttpStatusCode.Conflict)
    } catch (_: TeachDraftPreviewConflict) {
        checkPeer()
        respond(HttpStatusCode.Conflict)
    } catch (_: InvalidTeachSupplierDraft) {
        checkPeer()
        respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid_message"))
    } catch (_: InvalidTeachUpload) {
        checkPeer()
        respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid_message"))
    } catch (_: TeachUploadTooLarge) {
        checkPeer()
        respond(HttpStatusCode.PayloadTooLarge, mapOf("error" to "upload_too_large"))
    } catch (_: WorkbookBudgetExceeded) {
        checkPeer()
        respond(HttpStatusCode.PayloadTooLarge, mapOf("error" to "upload_too_large"))
    } catch (_: InvalidWorkbook) {
        checkPeer()
        respond(HttpStatusCode.UnsupportedMediaType, mapOf("error" to "unsupported_workbook"))
    } catch (_: InvalidReference) {
        checkPeer()
        respond(HttpStatusCode.UnsupportedMediaType, mapOf("error" to "unsupported_reference"))
    } catch (_: IOException) {
        checkPeer()
        respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid_message"))
    } finally {
        // Invalidate only the result, never the running task or its worker slot.
        decision.cancel()
        if (acquiredUpload) uploadPermits.release()
    }
}

private val selectionOverrides = setOf("env", "environment", "supplier", "supplierkey", "profile", "profilekey",
    "revision", "revisionid", "generation", "hash", "sha256", "workbookhash", "workbooksha256", "storeid", "manifest")

private fun isSelectionOverride(header: String): Boolean {
    if (header.equals(HttpHeaders.ContentDisposition, ignoreCase = true) || header.equals(REFERENCE_FILENAME_HEADER, ignoreCase = true)) return true
    val key = header.lowercase(Locale.ROOT).replace('_', '-').removePrefix("x-").removePrefix("bm-")
        .removePrefix("teach-").replace("-", "")
    return key in selectionOverrides
}

private suspend fun ApplicationCall.denyWorkbookUpload(decision: TeachWorkbookAuthDecision) {
    when (decision) {
        TeachWorkbookAuthDecision.InvalidToken -> respond(HttpStatusCode.Unauthorized, ErrorResponse(message = "Invalid Firebase ID token"))
        TeachWorkbookAuthDecision.NotAdmin -> respond(HttpStatusCode.Forbidden,
            ErrorResponse(message = "Esta cuenta no está autorizada para acceder al panel de administración."))
        TeachWorkbookAuthDecision.NotNominated -> respond(HttpStatusCode.Forbidden, ErrorResponse(message = "Acceso a Enséñame no concedido."))
        TeachWorkbookAuthDecision.Allowed, is TeachWorkbookPrincipal -> error("An allowed decision cannot be denied")
    }
}

private class WorkbookDeadline : RuntimeException()
internal class TeachUploadTooLarge : RuntimeException()

private fun decodeFilename(values: List<String>?): String {
    fun invalid(): Nothing = throw InvalidTeachUpload()
    val wire = values?.singleOrNull()?.trim(' ', '\t') ?: invalid()
    if (!filenameWire.matches(wire)) invalid()
    val decoded = try { Base64.getUrlDecoder().decode(wire) } catch (_: IllegalArgumentException) { invalid() }
    if (decoded.size > 255 || Base64.getUrlEncoder().withoutPadding().encodeToString(decoded) != wire) invalid()
    val name = try { Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(decoded)).toString() }
    catch (_: CharacterCodingException) { invalid() }
    if (name.any { it == '/' || it == '\\' || it.isISOControl() || isWorkbookBidi(it) || it == '\uFEFF' }) invalid()
    return name
}

private fun validateExtension(name: String, extension: String) {
    val ext = name.takeLast(extension.length).map { if (it in 'A'..'Z') it.lowercaseChar() else it }.joinToString("")
    if (ext != extension || name.dropLast(extension.length).isBlank()) throw InvalidTeachUpload()
}
