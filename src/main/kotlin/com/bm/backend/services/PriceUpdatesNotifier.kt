package com.bm.backend.services

import com.bm.backend.models.PriceUpdatesNotification

interface PriceUpdatesNotifier {
    suspend fun notify(notification: PriceUpdatesNotification)
}

class NoOpPriceUpdatesNotifier : PriceUpdatesNotifier {
    override suspend fun notify(notification: PriceUpdatesNotification) = Unit
}
