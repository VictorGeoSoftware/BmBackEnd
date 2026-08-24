package com.bm.backend.repositories.ports

/**
 * Reports whether the database is currently reachable.
 *
 * Exists so the readiness probe can ask "can we serve traffic?" without the
 * transport layer importing a persistence framework. The health route
 * previously called Exposed's `transaction { }` directly, which is the same
 * boundary violation that [TransactionRunnerPort] was introduced to remove.
 */
interface DatabaseHealthPort {

    /**
     * Returns `true` if a trivial query succeeds, `false` if the database is
     * unreachable. Implementations must not throw: an unreachable database is
     * an expected answer here, not an error.
     */
    fun isReachable(): Boolean
}
