package com.bm.backend.repositories

import com.bm.backend.repositories.ports.TransactionRunnerPort
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * Exposed-backed [TransactionRunnerPort].
 *
 * Exposed keeps the active transaction in a thread local, so repository calls
 * made inside [inTransaction] automatically join it rather than opening their
 * own — which is what makes the multi-table wipe in `GrantedUsersService`
 * atomic.
 */
class ExposedTransactionRunner : TransactionRunnerPort {

    override fun <T> inTransaction(block: () -> T): T = transaction { block() }
}
