package com.bm.backend.testing

import com.bm.backend.repositories.ports.TransactionRunnerPort

/**
 * [TransactionRunnerPort] that simply runs the block inline.
 *
 * The in-memory repositories are not transactional, so there is nothing to
 * begin or roll back. This lets services that express a unit of work be unit
 * tested without a database — previously such a test failed with
 * "Please call Database.connect() before using this code" because the service
 * called Exposed directly.
 *
 * Note this does NOT verify rollback behaviour; atomicity is exercised by the
 * Postgres integration tests.
 */
class DirectTransactionRunner : TransactionRunnerPort {

    /** Number of units of work executed, so tests can assert one was used. */
    var executions: Int = 0
        private set

    override fun <T> inTransaction(block: () -> T): T {
        executions++
        return block()
    }
}
