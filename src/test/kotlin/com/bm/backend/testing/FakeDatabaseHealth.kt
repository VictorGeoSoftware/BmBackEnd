package com.bm.backend.testing

import com.bm.backend.repositories.ports.DatabaseHealthPort

/**
 * In-memory [DatabaseHealthPort] whose answer is set by the test, so readiness
 * behaviour can be exercised without a database or Docker.
 */
class FakeDatabaseHealth(
    var reachable: Boolean = true
) : DatabaseHealthPort {

    var callCount: Int = 0
        private set

    override fun isReachable(): Boolean {
        callCount++
        return reachable
    }
}
