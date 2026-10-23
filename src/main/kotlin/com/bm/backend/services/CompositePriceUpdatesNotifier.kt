package com.bm.backend.services

import com.bm.backend.models.PriceUpdatesNotification
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory

/** Fans a notification out to every channel; one failing channel never blocks the others. */
class CompositePriceUpdatesNotifier(
    private val delegates: List<PriceUpdatesNotifier>,
) : PriceUpdatesNotifier {

    private val logger = LoggerFactory.getLogger(CompositePriceUpdatesNotifier::class.java)

    override suspend fun notify(notification: PriceUpdatesNotification) {
        delegates.forEach { delegate ->
            try {
                delegate.notify(notification)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error(
                    "Price-updates channel failed. channel={}, error={}",
                    delegate::class.simpleName, e.message, e
                )
            }
        }
    }
}
