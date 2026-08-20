package com.bm.backend.plugins

import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.request.header
import io.ktor.server.response.header
import io.ktor.util.AttributeKey
import java.util.UUID

/** Header used to carry the correlation id in and out of this service. */
const val REQUEST_ID_HEADER: String = "X-Request-Id"

/** MDC key under which the correlation id is exposed to every log statement. */
const val REQUEST_ID_MDC_KEY: String = "requestId"

private val RequestIdAttributeKey = AttributeKey<String>("BmRequestId")

private const val MAX_REQUEST_ID_LENGTH = 128

/**
 * Inbound ids come from an untrusted client and are written straight into the
 * logs, so anything that could forge log records (newlines) or blow up storage
 * (unbounded length) is rejected in favour of a freshly generated id.
 */
private fun sanitizeIncomingRequestId(raw: String?): String? {
    val candidate = raw?.trim().orEmpty()
    if (candidate.isEmpty() || candidate.length > MAX_REQUEST_ID_LENGTH) return null
    val isSafe = candidate.all { it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' }
    return if (isSafe) candidate else null
}

/**
 * Correlation id for the current call.
 *
 * Reuses the caller's [REQUEST_ID_HEADER] when present (so Nginx, the mobile app
 * or an upstream service can tie their own traces to ours) and generates one
 * otherwise. Computed once per call and cached in the call attributes.
 */
val ApplicationCall.requestId: String
    get() = attributes.computeIfAbsent(RequestIdAttributeKey) {
        sanitizeIncomingRequestId(request.header(REQUEST_ID_HEADER)) ?: UUID.randomUUID().toString()
    }

/**
 * Assigns a correlation id to every call and echoes it back to the client.
 *
 * This is what makes a single bill upload traceable across
 * Nginx -> backend -> Docling -> n8n: the id is put in the MDC by `CallLogging`
 * (see `Application.configurePlugins`) and forwarded on outbound HTTP calls by
 * `ExternalApiService`.
 */
val RequestIdPlugin = createApplicationPlugin(name = "RequestId") {
    onCall { call ->
        call.response.header(REQUEST_ID_HEADER, call.requestId)
    }
}
