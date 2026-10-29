package com.bm.backend.testing

import com.bm.backend.models.N8nWebhookResponse
import kotlinx.serialization.json.Json

/** Loads synthetic n8n webhook fixtures and decodes them like ExternalApiService does. */
object N8nFixtures {
    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun text(name: String): String =
        requireNotNull(N8nFixtures::class.java.getResource("/n8n/$name")) { "Missing fixture $name" }.readText()

    fun response(name: String, cups: String? = null): N8nWebhookResponse {
        val raw = text(name).let { if (cups == null) it else it.replace("ES0000000000000000AA0F", cups) }
        return json.decodeFromString(N8nWebhookResponse.serializer(), raw)
    }
}
