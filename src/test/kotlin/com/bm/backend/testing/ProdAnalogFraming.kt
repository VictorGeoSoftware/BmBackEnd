package com.bm.backend.testing

import io.netty.handler.codec.http.DefaultHttpHeaders
import io.netty.handler.codec.http.HttpDecoderConfig
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.codec.http.HttpHeaders
import io.netty.handler.codec.http.HttpHeadersFactory
import io.netty.handler.codec.http.HttpServerCodec
import io.netty.util.AsciiString

private val canonicalLength = Regex("(?:0|[1-9][0-9]{0,18})")

/** The decoder adds each field here BEFORE normalizing or removing framing headers. */
internal fun prodAnalogCodec() = HttpServerCodec(HttpDecoderConfig().setHeadersFactory(object : HttpHeadersFactory {
    override fun newHeaders(): HttpHeaders = object : DefaultHttpHeaders() {
        override fun add(name: CharSequence, value: Any): HttpHeaders {
            if (AsciiString.contentEqualsIgnoreCase(name, HttpHeaderNames.CONTENT_LENGTH)) {
                val length = value.toString()
                require(!contains(HttpHeaderNames.CONTENT_LENGTH) && !contains(HttpHeaderNames.TRANSFER_ENCODING) &&
                    canonicalLength.matches(length) && length.toLongOrNull() != null) { "Invalid fixture framing" }
            } else if (AsciiString.contentEqualsIgnoreCase(name, HttpHeaderNames.TRANSFER_ENCODING)) {
                require(!contains(HttpHeaderNames.CONTENT_LENGTH) && !contains(HttpHeaderNames.TRANSFER_ENCODING) &&
                    value.toString().equals("chunked", ignoreCase = true)) { "Invalid fixture framing" }
            }
            return super.add(name, value)
        }
    }
    override fun newEmptyHeaders() = newHeaders()
}))
