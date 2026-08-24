package com.bm.backend.repositories.ports

/**
 * Runs a block of repository calls as a single unit of work.
 *
 * Exists so that services can express "these writes must succeed or fail
 * together" without importing a persistence framework. Calling Exposed's
 * `transaction { }` from a service reaches past the repository layer into
 * infrastructure, which both breaks the architecture boundary and makes the
 * service impossible to unit test — the in-memory repositories are bypassed
 * and the call fails with "Please call Database.connect() before using this
 * code".
 */
interface TransactionRunnerPort {

    /**
     * Executes [block] inside a transaction, committing on success and rolling
     * back if it throws. Repository calls made within [block] join the same
     * transaction.
     */
    fun <T> inTransaction(block: () -> T): T
}
