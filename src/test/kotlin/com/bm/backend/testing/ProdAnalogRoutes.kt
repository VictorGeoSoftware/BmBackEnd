package com.bm.backend.testing

import com.bm.backend.models.TeachWorkbookAuthDecision
import com.bm.backend.models.TeachWorkbookPrincipal
import com.bm.backend.repositories.ports.TeachProposalStoreUnavailable
import com.bm.backend.routes.*
import com.bm.backend.services.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.netty.NettyApplicationCall
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.cancel
import io.netty.handler.codec.http.DefaultFullHttpResponse
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.codec.http.HttpVersion
import io.netty.util.AttributeKey
import kotlinx.coroutines.*
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*
import java.io.IOException
import java.util.UUID
import java.util.concurrent.Semaphore
import kotlin.time.Duration.Companion.nanoseconds

internal const val PROD_ANALOG_POST = "/prod-analog/proposal-import"
internal const val PROD_ANALOG_LIST = "/prod-analog/proposal-imports"
internal const val PROD_ANALOG_PREFLIGHT = "/prod-analog/fixture-identity"
internal val prodAnalogDeferredContinue: AttributeKey<Boolean> = AttributeKey.valueOf("bm.prod-analog.deferredContinue")
private const val challengeHeader = "X-Bm-Analog-Challenge"
private const val nonceHeader = "X-Bm-Analog-Nonce"
private const val admissionHeader = "X-Bm-Analog-Admit"
private val challengeWire = Regex("[0-9a-f]{32}")
private val bearerWire = Regex("Bearer ([0-9a-f]{64})")
private val cursorWire = Regex("cursor=([1-9][0-9]{0,18})")
private val lengthWire = Regex("[1-9][0-9]{0,18}")
private val incidental = setOf("host", "connection", "accept", "accept-language", "accept-encoding", "user-agent",
    "authorization", "pragma", "cache-control", "cookie", "referer", "origin", "sec-fetch-dest", "sec-fetch-mode",
    "sec-fetch-site", "sec-ch-ua", "sec-ch-ua-mobile", "sec-ch-ua-platform", "priority", "dnt", "sec-gpc")
private class AnalogFailure(val status: Int) : RuntimeException()

internal fun Route.prodAnalogRoutes(service: ProdAnalogImportService, auth: ProdAnalogAuthorization, binding: ProdAnalogBinding) {
    check(auth.binding == binding && service.binding == binding)
    val workers = TeachWorkbookAuthWorkers(decide = auth::verify)
    application.environment.monitor.subscribe(ApplicationStopped) { workers.close() }
    val slots = Semaphore(4)
    val executor = Dispatchers.IO.limitedParallelism(4)
    route(PROD_ANALOG_PREFLIGHT) { handle {
        call.closedResponse()
        val headers = call.request.headers
        val challenge = headers.getAll(challengeHeader)?.singleOrNull()?.takeIf(challengeWire::matches)
        if (call.request.httpMethod != HttpMethod.Head || call.request.uri != "/api/v1$PROD_ANALOG_PREFLIGHT" || challenge == null ||
            headers.names().any { it.lowercase() !in setOf("host", "connection", challengeHeader.lowercase()) }) {
            call.respond(HttpStatusCode.BadRequest)
        } else {
            call.response.header(nonceHeader, challenge)
            call.respond(HttpStatusCode.NoContent)
        }
    } }
    for (listing in listOf(false, true)) route(if (listing) PROD_ANALOG_LIST else PROD_ANALOG_POST) { handle {
        call.closedResponse()
        val method = if (listing) HttpMethod.Get else HttpMethod.Post
        val expectedPath = "/api/v1" + if (listing) PROD_ANALOG_LIST else PROD_ANALOG_POST
        if (call.request.path() != expectedPath) { call.respond(HttpStatusCode.NotFound); return@handle }
        if (call.request.httpMethod != method) {
            call.response.header(HttpHeaders.Allow, method.value)
            call.respond(HttpStatusCode.MethodNotAllowed); return@handle
        }
        val headers = call.request.headers
        val challenge = headers.getAll(challengeHeader)?.let { values ->
            values.singleOrNull()?.takeIf(challengeWire::matches) ?: ""
        }
        if (challenge == "") { call.respond(HttpStatusCode.BadRequest); return@handle }
        if (challenge != null) call.response.header(nonceHeader, challenge)
        val token = headers.getAll(HttpHeaders.Authorization)?.singleOrNull()?.let(bearerWire::matchEntire)?.groupValues?.get(1)
        if (token == null) { call.respond(HttpStatusCode.Unauthorized); return@handle }
        call.withWorkbookPeer { peer ->
            if (!slots.tryAcquire()) { call.respond(HttpStatusCode.TooManyRequests); return@withWorkbookPeer }
            val start = System.nanoTime()
            fun remainingNanos() = 15_000_000_000L - (System.nanoTime() - start)
            fun remaining() = (remainingNanos() / 1_000_000).also { if (it <= 0) throw AnalogFailure(408) }
            var authFinished: CompletableDeferred<Unit>? = null
            try {
                val done = withTimeoutOrNull(remainingNanos().nanoseconds) {
                    val context = currentCoroutineContext()
                    val checkpoint = { context.ensureActive(); peer(); remaining(); Unit }
                    suspend fun verify(): TeachWorkbookPrincipal {
                        checkpoint()
                        val finished = CompletableDeferred<Unit>()
                        val task = workers.submit(token, onFinished = { finished.complete(Unit) }) ?: throw AnalogFailure(429)
                        authFinished = finished
                        val result = try { task.await().getOrElse { throw AnalogFailure(503) }.also { finished.await() } }
                            finally { task.cancel() }
                        checkpoint()
                        return result.principal()
                    }
                    val principal = verify()
                    val sameActor = { checkpoint(); if (auth.current(token).principal() != principal) throw AnalogFailure(403) }
                    val actor = UUID.fromString(principal.uid)
                    val transport = setOf(challengeHeader.lowercase(), "content-length")
                    if (headers.getAll(HttpHeaders.ContentEncoding) != null) throw AnalogFailure(if (listing) 400 else 415)
                    val allowed = incidental + transport + if (listing) emptySet() else setOf("content-type", "transfer-encoding",
                        WORKBOOK_FILENAME_HEADER.lowercase(), PROPOSAL_IMPORT_ATTEMPT_HEADER.lowercase(), "expect")
                    if (headers.names().any { it.lowercase() !in allowed }) throw InvalidTeachUpload()
                    val lengths = headers.getAll(HttpHeaders.ContentLength)
                    val rawQuery = call.request.uri.substringAfter('?', "").takeIf { '?' in call.request.uri }
                    val json: ByteArray
                    val status: Int
                    if (listing) {
                        if (lengths != null && lengths != listOf("0")) throw InvalidTeachUpload()
                        val cursor = rawQuery?.let { cursorWire.matchEntire(it)?.groupValues?.get(1)?.toLongOrNull() ?: throw InvalidTeachUpload() }
                        val page = withContext(executor) { sameActor(); service.list(actor, cursor, ::remaining, checkpoint) }
                        json = Json.encodeToString(ProdAnalogPage.serializer(), page).encodeToByteArray()
                        status = 200
                    } else {
                        if (rawQuery != null) throw InvalidTeachUpload()
                        val encodings = headers.getAll(HttpHeaders.TransferEncoding)
                        if (encodings != null && (lengths != null || !encodings.singleOrNull().equals("chunked", ignoreCase = true))) throw InvalidTeachUpload()
                        val length = if (lengths == null) null else lengths.singleOrNull()?.takeIf(lengthWire::matches)?.toLongOrNull() ?: throw InvalidTeachUpload()
                        if (length == null && encodings == null) throw InvalidTeachUpload()
                        if (length != null && length > WORKBOOK_BODY_LIMIT) throw TeachUploadTooLarge()
                        val types = headers.getAll(HttpHeaders.ContentType)
                        if (types != null && (types.size != 1 || ',' in types.single())) throw InvalidTeachUpload()
                        if (!types?.singleOrNull().equals(WORKBOOK_MEDIA_TYPE, ignoreCase = true)) throw AnalogFailure(415)
                        val filename = decodeFilename(headers.getAll(WORKBOOK_FILENAME_HEADER)).also { validateExtension(it, ".xlsx") }
                        val attempt = headers.getAll(PROPOSAL_IMPORT_ATTEMPT_HEADER)?.singleOrNull()?.takeIf(teachProposalAttemptId::matches) ?: throw InvalidTeachUpload()
                        headers.getAll(HttpHeaders.Expect)?.let { expect ->
                            val netty = (call as? RoutingApplicationCall)?.engineCall as? NettyApplicationCall ?: throw InvalidTeachUpload()
                            if (expect != listOf("100-continue") || challenge == null ||
                                netty.context.channel().attr(prodAnalogDeferredContinue).get() != true) throw InvalidTeachUpload()
                            sameActor()
                            val signal = DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.CONTINUE)
                            signal.headers().set(admissionHeader, challenge)
                            netty.context.channel().writeAndFlush(signal)
                        }
                        sameActor()
                        val (bytes, size) = call.readAnalogWorkbook(length?.toInt(), checkpoint)
                        val receipt = withContext(executor) { service.ingest(actor, attempt, filename, bytes, size, ::remaining, checkpoint, sameActor) }
                        json = Json.encodeToString(ProdAnalogReceipt.serializer(), receipt).encodeToByteArray()
                        status = if (receipt.kind == "stored") 201 else 200
                    }
                    if (json.size > if (listing) 16 * 1024 else 4 * 1024) throw AnalogFailure(503)
                    if (verify() != principal) throw AnalogFailure(403)
                    sameActor()
                    call.respondBytes(json, ContentType.Application.Json, HttpStatusCode.fromValue(status))
                    true
                }
                if (done == null && !call.response.isCommitted) { peer(); call.respond(HttpStatusCode.RequestTimeout) }
            } catch (failure: Exception) {
                peer()
                if (failure is CancellationException || call.response.isCommitted) throw failure
                val (status, error) = if (remainingNanos() <= 0) 408 to null else when (failure) {
                    is AnalogFailure -> failure.status to null
                    is ProdAnalogConflict -> if (auth.current(token) is TeachWorkbookPrincipal) 409 to failure.wire else 403 to null
                    is TeachSupplierSelectionConflict -> 409 to failure.error.wire
                    is TeachDraftPreviewConflict -> 409 to "incompatible_workbook"
                    is TeachUploadTooLarge, is WorkbookBudgetExceeded -> 413 to "upload_too_large"
                    is InvalidWorkbook -> 415 to "unsupported_workbook"
                    is InvalidTeachUpload, is IOException -> 400 to "invalid_message"
                    is ProdAnalogUnavailable, is TeachProposalStoreUnavailable, is SerializationException -> 503 to null
                    else -> throw failure
                }
                if (error == null) call.respond(HttpStatusCode.fromValue(status))
                else call.respondBytes(buildJsonObject { put("error", error) }.toString().encodeToByteArray(),
                    ContentType.Application.Json, HttpStatusCode.fromValue(status))
            } finally {
                val finished = authFinished
                if (finished == null) slots.release() else finished.invokeOnCompletion { slots.release() }
            }
        }
    } }
}

private fun TeachWorkbookAuthDecision.principal() = this as? TeachWorkbookPrincipal
    ?: throw AnalogFailure(if (this == TeachWorkbookAuthDecision.InvalidToken) 401 else 403)

private fun ApplicationCall.closedResponse() {
    response.header(HttpHeaders.CacheControl, "no-store")
    if (request.local.version.startsWith("HTTP/1")) response.header(HttpHeaders.Connection, "close")
}

private suspend fun ApplicationCall.readAnalogWorkbook(length: Int?, checkpoint: () -> Unit): Pair<ByteArray, Int> {
    val input = receiveChannel()
    try {
        val bytes = ByteArray(length ?: WORKBOOK_BODY_LIMIT)
        var size = 0
        while (size < bytes.size) {
            checkpoint()
            val count = input.readAvailable(bytes, size, minOf(8192, bytes.size - size))
            if (count < 0) break
            size += count
        }
        if (size == bytes.size && input.readAvailable(ByteArray(1), 0, 1) >= 0) throw TeachUploadTooLarge()
        if (size == 0 || (length != null && size != length)) throw InvalidTeachUpload()
        checkpoint()
        return bytes to size
    } finally { input.cancel() }
}
