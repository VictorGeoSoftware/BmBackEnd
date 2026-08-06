package com.bm.backend.repositories.ports

import com.bm.backend.models.UserConsumption

/**
 * Port (Clean Architecture) for the user consumption store.
 *
 * The current production adapter is in-memory and intentionally non-persistent
 * (single-instance backend assumption). The port exists so that a future
 * Postgres-backed adapter can replace it transparently when the backend goes
 * multi-instance.
 */
interface UserConsumptionRepositoryPort {

    fun storeConsumptionData(consumptionReport: UserConsumption)

    fun getConsumptionReport(): UserConsumption?

    /**
     * Administrative operation: permanently deletes the consumption payload
     * stored for [uid]. Returns the number of rows deleted (0 or 1).
     */
    fun deleteByUid(uid: String): Int
}
