package com.bm.backend.routes

import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveChannel
import io.ktor.server.request.uri
import io.ktor.server.routing.RoutingApplicationCall
import io.ktor.server.netty.NettyApplicationCall
import io.ktor.utils.io.cancel
import io.netty.handler.codec.http.DefaultFullHttpResponse
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.codec.http.HttpVersion
import io.netty.util.AttributeKey
import java.util.UUID

internal const val PRODUCTION_PROMOTION_PATH = "/teach-me/production-promotion"
internal const val TEACH_PROMOTION_REF = "X-Teach-Promotion-Ref"
internal val teachPromotionDeferredContinue: AttributeKey<Boolean> = AttributeKey.valueOf("bm.teach.promotion.deferredContinue")
internal class PromotionRecheck(val status: Int) : RuntimeException()
internal class UnsupportedPromotionFraming : RuntimeException()

// Transport headers used by the browser or the local HTTP runtime; all other signals are rejected.
private val incidental = setOf("host", "connection", "accept", "accept-language", "accept-encoding", "user-agent",
    "authorization", "pragma", "cache-control", "cookie", "referer", "origin", "sec-fetch-dest", "sec-fetch-mode",
    "sec-fetch-site", "sec-ch-ua", "sec-ch-ua-mobile", "sec-ch-ua-platform", "priority", "dnt", "sec-gpc")

internal suspend fun ApplicationCall.readTeachPromotionRef(get: Boolean, checkpoint: () -> Unit): UUID {
    val headers = request.headers
    val types = headers.getAll(HttpHeaders.ContentType)
    val lengths = headers.getAll(HttpHeaders.ContentLength)
    val refs = headers.getAll(TEACH_PROMOTION_REF)
    if ('?' in request.uri || headers.getAll(HttpHeaders.TransferEncoding) != null) throw InvalidTeachUpload()
    if (headers.getAll(HttpHeaders.ContentEncoding) != null) throw UnsupportedPromotionFraming()
    val allowed = incidental + if (get) setOf("content-length", TEACH_PROMOTION_REF.lowercase()) else setOf("content-length", "content-type", "expect")
    if (headers.names().any { it.lowercase() !in allowed }) throw InvalidTeachUpload()
    if (get) {
        if (types != null || (lengths != null && lengths != listOf("0"))) throw InvalidTeachUpload()
        return UUID.fromString(refs?.singleOrNull()?.takeIf(canonicalTeachValidationRef::matches) ?: throw InvalidTeachUpload())
    }
    if (types != null && (types.size != 1 || ',' in types.single())) throw InvalidTeachUpload()
    if (!types?.singleOrNull().equals("application/json", ignoreCase = true)) throw UnsupportedPromotionFraming()
    val length = lengths?.singleOrNull()?.takeIf { it.matches(Regex("[1-9][0-9]{0,18}")) }
        ?.toLongOrNull() ?: throw InvalidTeachUpload()
    if (length > PROPOSAL_VALIDATION_LIMIT) throw TeachUploadTooLarge()
    checkpoint()
    // Fixture transport only: the Pages proxy does not open its body until this signal.
    // The usual Netty Expect handler runs BEFORE authorization, so fixture composition defers it.
    headers.getAll(HttpHeaders.Expect)?.let { expects ->
        val engineCall = (this as? RoutingApplicationCall)?.engineCall ?: this
        val ctx = (engineCall as? NettyApplicationCall)?.context ?: throw InvalidTeachUpload()
        if (expects != listOf("100-continue") || ctx.channel().attr(teachPromotionDeferredContinue).get() != true) throw InvalidTeachUpload()
        val signal = DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.CONTINUE)
        signal.headers().set("X-Teach-Promotion-Continue", "fixture-v1")
        ctx.channel().writeAndFlush(signal)
    }
    val channel = receiveChannel()
    return try { readTeachConfirmedRef(channel, length, "confirmPromotion", checkpoint).ref } finally { channel.cancel() }
}
