package com.bm.backend.services

import com.bm.backend.models.PriceUpdatesEventType
import com.bm.backend.models.PriceUpdatesNotification
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class CompositePriceUpdatesNotifierTest {

    private class Recording(private val fail: Boolean = false) : PriceUpdatesNotifier {
        val received = mutableListOf<PriceUpdatesNotification>()
        override suspend fun notify(notification: PriceUpdatesNotification) {
            received += notification
            if (fail) error("channel down")
        }
    }

    private val notification = PriceUpdatesNotification(PriceUpdatesEventType.PRICE_PROPOSALS_DELETED, listOf(3), 1)

    @Test
    fun `web channel still runs when the Android channel throws`() = runBlocking {
        val android = Recording(fail = true)
        val web = Recording()
        CompositePriceUpdatesNotifier(listOf(android, web)).notify(notification)

        assertEquals(listOf(notification), android.received)
        assertEquals(listOf(notification), web.received)
    }

    @Test
    fun `Android channel still runs when the web channel throws`() = runBlocking {
        val web = Recording(fail = true)
        val android = Recording()
        CompositePriceUpdatesNotifier(listOf(web, android)).notify(notification)

        assertEquals(listOf(notification), android.received)
    }
}
