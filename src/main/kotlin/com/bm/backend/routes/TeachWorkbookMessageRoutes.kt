package com.bm.backend.routes

import com.bm.backend.models.ErrorResponse
import com.bm.backend.models.TeachReferenceAttachment
import com.bm.backend.models.TeachReferenceReceipt
import com.bm.backend.models.TeachWorkbookAttachment
import com.bm.backend.models.TeachWorkbookAuthDecision
import com.bm.backend.models.TeachWorkbookReceipt
import com.bm.backend.services.InvalidWorkbook
import com.bm.backend.services.InvalidReference
import com.bm.backend.services.TeachReferenceValidator
import com.bm.backend.services.TeachWorkbookAuthWorkers
import com.bm.backend.services.TeachWorkbookValidator
import com.bm.backend.services.WorkbookBudgetExceeded
import com.bm.backend.services.isWorkbookBidi
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
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
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingApplicationCall
import io.ktor.server.routing.application
import io.ktor.server.routing.post
import io.ktor.utils.io.cancel
import io.netty.channel.ChannelFutureListener
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.util.Base64
import java.util.concurrent.Semaphore
import kotlin.time.Duration.Companion.nanoseconds

internal const val WORKBOOK_MEDIA_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
internal const val WORKBOOK_FILENAME_HEADER = "X-Teach-Workbook-Filename"
internal const val WORKBOOK_BODY_LIMIT = 5 * 1024 * 1024
private const val WORKBOOK_MESSAGE_PATH = "/teach-me/messages"
private const val REFERENCE_PATH = "/teach-me/references"
internal const val REFERENCE_FILENAME_HEADER = "X-Teach-Reference-Filename"
internal const val REFERENCE_BODY_LIMIT = 2 * 1024 * 1024
private val filenameWire = Regex("[A-Za-z0-9_-]{1,340}")
private class InvalidTeachUpload : RuntimeException()

internal fun ApplicationCall.isTeachUpload() = request.httpMethod == HttpMethod.Post &&
    request.path().trimEnd('/').let { it == "/api/v1$WORKBOOK_MESSAGE_PATH" || it == "/api/v1$REFERENCE_PATH" }

internal fun Route.teachWorkbookMessageRoutes(
    validator: TeachWorkbookValidator = TeachWorkbookValidator(),
    timeoutMillis: Long = 15_000,
    beforeUpload: suspend () -> Unit = {},
    authorize: (String) -> TeachWorkbookAuthDecision,
) {
    // Both POST routes use this one set of workers and permits; GET is independent.
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
    for (path in listOf(WORKBOOK_MESSAGE_PATH, REFERENCE_PATH)) post(path) {
        if (call.response.headers[HttpHeaders.CacheControl] == null) call.response.header(HttpHeaders.CacheControl, "no-store")
        // A direct HTTP/1 caller should not reuse the connection after an early rejection.
        if (call.request.local.version.startsWith("HTTP/1")) call.response.header(HttpHeaders.Connection, "close")

        val authHeader = call.request.headers[HttpHeaders.Authorization]
        if (authHeader.isNullOrBlank() || !authHeader.startsWith("Bearer ")) {
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
                path == REFERENCE_PATH, beforeUpload, timeoutMillis, checkPeer)
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
    beforeUpload: suspend () -> Unit,
    timeoutMillis: Long,
    checkPeer: () -> Unit,
) {
    val admitted = System.nanoTime()
    fun remaining() = timeoutMillis * 1_000_000 - (System.nanoTime() - admitted)
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
            if (authorization != TeachWorkbookAuthDecision.Allowed) {
                return@withTimeoutOrNull authorization
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
            if (reference) {
                referenceValidator.validate(bytes, size, pdf, checkpoint)
                checkpoint()
                TeachReferenceReceipt(TeachReferenceAttachment(if (pdf) "reference_pdf" else "reference_png", filename, size))
            } else {
                validator.validate(bytes, size, checkpoint)
                checkpoint()
                TeachWorkbookReceipt(TeachWorkbookAttachment("workbook", filename, size))
            }
        }
        currentCoroutineContext().ensureActive()
        checkPeer()
        when {
            outcome == null || remaining() <= 0 -> respond(HttpStatusCode.RequestTimeout)
            outcome is TeachWorkbookAuthDecision -> denyWorkbookUpload(outcome)
            outcome is HttpStatusCode -> respond(outcome)
            outcome is TeachWorkbookReceipt -> respond(HttpStatusCode.OK, outcome)
            outcome is TeachReferenceReceipt -> respond(HttpStatusCode.OK, outcome)
            else -> error("Unexpected Enséñame upload outcome")
        }
    } catch (_: WorkbookDeadline) {
        checkPeer()
        respond(HttpStatusCode.RequestTimeout)
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

private suspend fun ApplicationCall.denyWorkbookUpload(decision: TeachWorkbookAuthDecision) {
    when (decision) {
        TeachWorkbookAuthDecision.InvalidToken -> respond(HttpStatusCode.Unauthorized, ErrorResponse(message = "Invalid Firebase ID token"))
        TeachWorkbookAuthDecision.NotAdmin -> respond(HttpStatusCode.Forbidden,
            ErrorResponse(message = "Esta cuenta no está autorizada para acceder al panel de administración."))
        TeachWorkbookAuthDecision.NotNominated -> respond(HttpStatusCode.Forbidden, ErrorResponse(message = "Acceso a Enséñame no concedido."))
        TeachWorkbookAuthDecision.Allowed -> error("An allowed decision cannot be denied")
    }
}

private class WorkbookDeadline : RuntimeException()
private class TeachUploadTooLarge : RuntimeException()

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
