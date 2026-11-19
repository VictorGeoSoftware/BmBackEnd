package com.bm.backend.testing

import com.bm.backend.configurePlugins
import io.ktor.server.application.*
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.engine.*
import io.ktor.server.netty.Netty
import io.ktor.server.request.*
import io.ktor.server.routing.*
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelFutureListener
import io.netty.handler.codec.http.DefaultFullHttpResponse
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.codec.http.HttpRequest
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.codec.http.HttpServerExpectContinueHandler
import io.netty.handler.codec.http.HttpVersion
import io.netty.util.ReferenceCountUtil
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicInteger

internal fun <T> prodAnalogNetty(service: ProdAnalogImportService, auth: ProdAnalogAuthorization,
    receives: AtomicInteger = AtomicInteger(), otherRoutes: (Route.() -> Unit)? = null,
    run: (ProdAnalogEndpoint, TeachCallCompletion) -> T): T {
    val completion = TeachCallCompletion()
    val tls = ProdAnalogTls()
    val server = embeddedServer(Netty, applicationEngineEnvironment {
        config = MapApplicationConfig()
        sslConnector(tls.store, "fixture", { charArrayOf() }, { charArrayOf() }) { host = "127.0.0.1"; port = 0 }
        module {
            if (otherRoutes != null) configurePlugins()
            completion.install(this) { it.request.path() in setOf("/api/v1$PROD_ANALOG_POST", "/api/v1$PROD_ANALOG_LIST") }
            receivePipeline.intercept(ApplicationReceivePipeline.Before) { receives.incrementAndGet() }
            routing { route("/api/v1") { prodAnalogRoutes(service, auth, service.binding); otherRoutes?.invoke(this) } }
        }
    }) {
        httpServerCodec = ::prodAnalogCodec
        channelPipelineConfig = {
            if (context("codec") == null) channel().close() // The closed fixture wire is HTTP/1.1 only.
            else replace("continue", "prod-analog-fixture-continue", object : HttpServerExpectContinueHandler() {
                override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
                    if (msg is HttpRequest && msg.decoderResult().isFailure) {
                        ReferenceCountUtil.release(msg)
                        val rejection = DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.BAD_REQUEST)
                        rejection.headers().set(HttpHeaderNames.CONNECTION, "close").set(HttpHeaderNames.CACHE_CONTROL, "no-store")
                            .setInt(HttpHeaderNames.CONTENT_LENGTH, 0)
                        ctx.writeAndFlush(rejection).addListener(ChannelFutureListener.CLOSE)
                    } else if (msg is HttpRequest && msg.uri().substringBefore('?') == "/api/v1$PROD_ANALOG_POST") {
                        ctx.channel().attr(prodAnalogDeferredContinue).set(true)
                        ctx.fireChannelRead(msg)
                    } else super.channelRead(ctx, msg)
                }
            })
        }
    }
    return try {
        server.start(wait = false)
        run(ProdAnalogEndpoint(runBlocking { server.resolvedConnectors().single().port }, tls.certificate), completion)
    } finally { server.stop(100, 1_000) }
}
