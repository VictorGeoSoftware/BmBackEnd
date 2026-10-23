package com.bm.backend.services

import com.bm.backend.models.PriceUpdatesEventType
import com.bm.backend.models.PriceUpdatesNotification
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

/**
 * Coalesces bursts of `PRICE_PROPOSALS_UPSERTED` notifications into a single one.
 *
 * BmWeb uploads a batch of price proposals as one request per file, so a 30-file
 * batch previously produced 30 FCM messages. BmApp reacts to each one by calling
 * `refreshActiveStudy()`, which means a broker sitting in front of a customer
 * would watch the proposals recompute repeatedly against a half-loaded price set.
 * The intermediate states are not just noisy, they are wrong.
 *
 * Coalescing is trailing-only and deliberately unbounded: as long as files keep
 * arriving the notification keeps being deferred, so the app is told once, after
 * the batch settles, when the data is coherent. The cost is that a long batch
 * delays the refresh for its whole duration — which is the intended trade, since
 * refreshing earlier would only show incomplete prices.
 *
 * Deletions and clears are not batched by any caller, so they pass straight
 * through; any pending upsert is flushed first to preserve ordering.
 */
class DebouncingPriceUpdatesNotifier(
    private val delegate: PriceUpdatesNotifier,
    private val windowMillis: Long = DEFAULT_WINDOW_MILLIS,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : PriceUpdatesNotifier, AutoCloseable {

    private val logger = LoggerFactory.getLogger(DebouncingPriceUpdatesNotifier::class.java)

    private val mutex = Mutex()
    private var pending: PriceUpdatesNotification? = null
    private var flushJob: Job? = null

    override suspend fun notify(notification: PriceUpdatesNotification) {
        if (notification.eventType != PriceUpdatesEventType.PRICE_PROPOSALS_UPSERTED) {
            flushPending()
            delegate.notify(notification)
            return
        }

        mutex.withLock {
            pending = pending?.let { merge(it, notification) } ?: notification
            flushJob?.cancel()
            flushJob = scope.launch {
                delay(windowMillis)
                flushPending()
            }
        }
    }

    private suspend fun flushPending() {
        val timer = mutex.withLock {
            val current = flushJob
            flushJob = null
            current
        }
        timer?.cancel()

        val notification = mutex.withLock {
            val current = pending
            pending = null
            current
        } ?: return

        logger.info(
            "Flushing coalesced price-updates notification. changedCount={}, windowMillis={}",
            notification.changedCount,
            windowMillis,
        )

        // The send must not be killed by the timer cancellation above racing it.
        withContext(NonCancellable) {
            delegate.notify(notification)
        }
    }

    private fun merge(
        accumulated: PriceUpdatesNotification,
        incoming: PriceUpdatesNotification,
    ): PriceUpdatesNotification = PriceUpdatesNotification(
        eventType = PriceUpdatesEventType.PRICE_PROPOSALS_UPSERTED,
        changedIds = (accumulated.changedIds + incoming.changedIds).distinct(),
        changedCount = accumulated.changedCount + incoming.changedCount,
        timestamp = incoming.timestamp,
    )

    override fun close() {
        scope.cancel()
    }

    companion object {
        /**
         * Comfortably longer than the gap between two files finishing in a batch
         * (measured extraction is 3-46s per PDF, five in flight), so a batch
         * collapses to one notification, while a single upload still refreshes
         * the app promptly.
         */
        const val DEFAULT_WINDOW_MILLIS: Long = 5_000L
    }
}
