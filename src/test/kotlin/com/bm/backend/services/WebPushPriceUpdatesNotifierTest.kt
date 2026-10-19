package com.bm.backend.services

import com.bm.backend.models.PriceUpdatesEventType
import com.bm.backend.models.PriceUpdatesNotification
import com.bm.backend.testing.InMemoryWebPushTokensRepository
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WebPushPriceUpdatesNotifierTest {

    private class FakeSender(
        private val errorFor: (String) -> String? = { null },
        private val throwOnCall: Int? = null,
    ) : WebPushSender {
        val calls = mutableListOf<Triple<List<String>, Map<String, String>, Long>>()

        override suspend fun sendMulticast(
            tokens: List<String>,
            data: Map<String, String>,
            ttlSeconds: Long,
        ): List<WebPushSender.SendOutcome> {
            calls += Triple(tokens, data, ttlSeconds)
            if (throwOnCall == calls.size) error("boom")
            return tokens.map { WebPushSender.SendOutcome(it, errorFor(it)) }
        }
    }

    private val notification = PriceUpdatesNotification(
        eventType = PriceUpdatesEventType.PRICE_PROPOSALS_UPSERTED,
        changedIds = listOf(1, 2),
        changedCount = 2,
        timestamp = "2026-10-19T10:00:00Z",
    )

    private fun repoWith(vararg tokens: String) = InMemoryWebPushTokensRepository().apply {
        tokens.forEach { upsert(it, "uid", "a@example.com", "android") }
    }

    @Test
    fun `sends the data-only price_updates payload with a one-day TTL`() = runBlocking {
        val sender = FakeSender()
        WebPushPriceUpdatesNotifier(repoWith("tok-1"), sender).notify(notification)

        val (tokens, data, ttl) = sender.calls.single()
        assertEquals(listOf("tok-1"), tokens)
        assertEquals(setOf("type", "payload"), data.keys)
        assertEquals("price_updates", data["type"])
        assertEquals(notification, Json.decodeFromString(PriceUpdatesNotification.serializer(), data.getValue("payload")))
        assertEquals(86_400L, ttl)
    }

    @Test
    fun `1201 tokens are sent in 3 chunks of at most 500`() = runBlocking {
        val repo = repoWith(*Array(1201) { "tok-$it" })
        val sender = FakeSender()
        WebPushPriceUpdatesNotifier(repo, sender).notify(notification)

        assertEquals(listOf(500, 500, 201), sender.calls.map { it.first.size })
        assertEquals(1201, sender.calls.flatMap { it.first }.toSet().size)
    }

    @Test
    fun `prune codes delete rows and other errors keep them`() = runBlocking {
        val codes = mapOf(
            "unreg" to "UNREGISTERED",
            "mismatch" to "SENDER_ID_MISMATCH",
            "invalid" to "INVALID_ARGUMENT",
            "unavailable" to "UNAVAILABLE",
            "internal" to "INTERNAL",
            "quota" to "QUOTA_EXCEEDED",
        )
        val repo = repoWith("ok", *codes.keys.toTypedArray())
        WebPushPriceUpdatesNotifier(repo, FakeSender(errorFor = codes::get)).notify(notification)

        assertEquals(listOf("internal", "ok", "quota", "unavailable"), repo.allTokens().sorted())
    }

    @Test
    fun `no tokens means no send`() = runBlocking {
        val sender = FakeSender()
        WebPushPriceUpdatesNotifier(InMemoryWebPushTokensRepository(), sender).notify(notification)
        assertTrue(sender.calls.isEmpty())
    }

    @Test
    fun `a failing chunk is logged and the next chunk is still sent`() = runBlocking {
        val repo = repoWith(*Array(600) { "tok-$it" })
        val sender = FakeSender(throwOnCall = 1)
        WebPushPriceUpdatesNotifier(repo, sender).notify(notification)

        assertEquals(2, sender.calls.size)
        assertEquals(600, repo.allTokens().size)
    }
}
