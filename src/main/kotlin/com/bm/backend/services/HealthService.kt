package com.bm.backend.services

import com.bm.backend.repositories.ports.DatabaseHealthPort

/**
 * Decides what "alive" and "ready" mean for this service.
 *
 * The distinction matters operationally:
 *
 * - **Liveness** answers "is this process wedged?" and must not touch any
 *   dependency. Docker restarts a container whose liveness probe fails, so
 *   checking the database here means a database blip restarts an otherwise
 *   healthy backend — turning a brief outage into a restart loop that makes
 *   recovery slower, not faster.
 * - **Readiness** answers "can this instance serve requests right now?" and
 *   legitimately depends on the database.
 */
class HealthService(
    private val databaseHealth: DatabaseHealthPort
) {

    /**
     * True whenever the process can execute code and respond. Deliberately has
     * no dependencies: if this method runs at all, the answer is yes.
     */
    fun isLive(): Boolean = true

    /** True when every dependency required to serve traffic is available. */
    fun isReady(): Boolean = databaseHealth.isReachable()
}
