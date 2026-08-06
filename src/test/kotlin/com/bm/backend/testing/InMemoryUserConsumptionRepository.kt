package com.bm.backend.testing

import com.bm.backend.models.UserConsumption
import com.bm.backend.repositories.ports.UserConsumptionRepositoryPort
import java.util.concurrent.CopyOnWriteArrayList

/**
 * In-memory test double for [UserConsumptionRepositoryPort].
 *
 * Stores at most one report (mirroring the current global-uid production
 * behavior) and records [deleteByUid] calls so wipe orchestration can be
 * asserted in service tests.
 */
class InMemoryUserConsumptionRepository : UserConsumptionRepositoryPort {

    private var report: UserConsumption? = null
    val deletedUids = CopyOnWriteArrayList<String>()

    override fun storeConsumptionData(consumptionReport: UserConsumption) {
        report = consumptionReport
    }

    override fun getConsumptionReport(): UserConsumption? = report

    override fun deleteByUid(uid: String): Int {
        deletedUids.add(uid)
        return 0
    }
}
