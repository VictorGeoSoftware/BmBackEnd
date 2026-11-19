package com.bm.backend.testing

import com.bm.backend.models.TeachActivationClaim
import com.bm.backend.models.TeachActivationResult
import com.bm.backend.models.TeachActiveSkill
import com.bm.backend.repositories.ports.TeachActivationStore
import com.bm.backend.services.TeachActivationUnavailable
import com.bm.backend.services.activationCheck
import java.sql.Connection
import java.sql.SQLException
import java.sql.Timestamp
import java.time.Instant
import java.util.concurrent.Executor

/** JDBC is only mounted by the fixture. The caller owns a shared permit until all resources close. */
internal class TeachActivationJdbcStore(
    override val storeId: String,
    private val database: String,
    private val role: String,
    private val connect: (() -> Long) -> Connection,
    private val beforeLock: (String) -> Unit,
    private val afterRead: (TeachActiveSkill) -> Unit,
) : TeachActivationStore {
    private class Transaction(val sql: Connection, val remainingMillis: () -> Long) {
        fun <T> query(query: String, args: List<Any?> = emptyList(), run: (java.sql.ResultSet) -> T): T {
            timeout()
            return sql.prepareStatement(query).use { statement ->
                args.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
                statement.executeQuery().use(run)
            }
        }

        fun execute(query: String, vararg args: Any?): Int {
            timeout()
            return sql.prepareStatement(query).use { statement ->
                args.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
                statement.executeUpdate()
            }
        }

        private fun timeout() {
            val ms = remainingMillis().toInt().coerceAtLeast(1)
            sql.setNetworkTimeout(Executor { it.run() }, ms)
            sql.createStatement().use { it.execute("SET LOCAL statement_timeout = $ms; SET LOCAL lock_timeout = $ms") }
            sql.setNetworkTimeout(Executor { it.run() }, remainingMillis().toInt().coerceAtLeast(1))
        }

        fun pointer(supplier: String, lock: Boolean) = query(
            "SELECT revision, generation FROM pilot.active WHERE supplier = ?" + if (lock) " FOR UPDATE" else "", listOf(supplier),
        ) { rows -> if (rows.next()) TeachActiveSkill(rows.getString(1), rows.getLong(2).toString()) else TeachActiveSkill(null, "0") }
    }

    private fun <T> transaction(manifest: String, remainingMillis: () -> Long, run: (Transaction) -> T): T {
        try {
            remainingMillis()
            return connect(remainingMillis).use { sql ->
                sql.autoCommit = false
                val tx = Transaction(sql, remainingMillis)
                try {
                    val identity = tx.query("SELECT current_database(), current_user, id, manifest FROM pilot.identity") { rows ->
                        rows.next() && rows.getString(1) == database && rows.getString(2) == role &&
                            rows.getString(3) == storeId && rows.getString(4) == manifest && !rows.next()
                    }
                    if (!identity) throw TeachActivationUnavailable()
                    run(tx) // Read-only paths and no-op end with rollback, not commit.
                } finally {
                    // Closing a failed/expired connection rolls back unless commit already went out.
                    // On success/ordinary conflicts, roll back outstanding read state with the prior bounded network timeout.
                    if (!sql.isClosed && runCatching { remainingMillis() }.isSuccess) runCatching { sql.rollback() }
                }
            }
        } catch (_: SQLException) {
            remainingMillis()
            throw TeachActivationUnavailable()
        }
    }

    override fun verify(manifest: String): Map<String, TeachActiveSkill> {
        val start = System.nanoTime()
        return transaction(manifest, { (5_000 - (System.nanoTime() - start) / 1_000_000).also { check(it > 0) } }) { tx ->
            tx.query("SELECT supplier, revision, generation FROM pilot.active") { rows ->
                buildMap { while (rows.next()) put(rows.getString(1), TeachActiveSkill(rows.getString(2), rows.getLong(3).toString())) }
            }
        }
    }

    override fun read(manifest: String, supplier: String, remainingMillis: () -> Long, checkpoint: () -> Unit): TeachActiveSkill =
        transaction(manifest, remainingMillis) { tx -> tx.pointer(supplier, false).also { checkpoint() } }

    override fun activate(manifest: String, claim: TeachActivationClaim, remainingMillis: () -> Long,
                          mutationTime: () -> Long): TeachActivationResult = transaction(manifest, remainingMillis) { tx ->
        val replay = tx.query("SELECT 1 FROM pilot.audit WHERE nonce = ?", listOf(claim.nonce)) { it.next() }
        activationCheck(!replay)
        beforeLock(claim.expected.generation)
        val previous = tx.pointer(claim.supplierKey, true)
        afterRead(previous)
        val now = mutationTime()
        activationCheck(previous == claim.expected)
        if (previous.revisionId == claim.revisionId) return@transaction TeachActivationResult("alreadyActive", previous)
        val generation = previous.generation.toLong()
        activationCheck(generation < Long.MAX_VALUE)
        val next = generation + 1
        val actual = if (previous.revisionId == null) tx.execute(
            "INSERT INTO pilot.active VALUES (?, ?, ?) ON CONFLICT (supplier) DO NOTHING", claim.supplierKey, claim.revisionId, next,
        ) else tx.execute(
            "UPDATE pilot.active SET revision = ?, generation = ? WHERE supplier = ? AND revision = ? AND generation = ?",
            claim.revisionId, next, claim.supplierKey, previous.revisionId, generation,
        )
        activationCheck(actual == 1)
        mutationTime() // A unique-key wait on first activation also consumes the request's budget.
        try {
            check(tx.execute("INSERT INTO pilot.audit VALUES (?, ?, ?, ?, ?, ?, 'qa', ?, 'activated')",
                claim.nonce, claim.supplierKey, claim.revisionId, previous.revisionId, next, claim.actor,
                Timestamp.from(Instant.ofEpochSecond(now))) == 1)
        } catch (failure: SQLException) {
            if (failure.sqlState == "23505") activationCheck(false)
            throw failure
        }
        tx.sql.setNetworkTimeout(Executor { it.run() }, remainingMillis().toInt().coerceAtLeast(1))
        mutationTime() // Last checkpoint before sending commit, not a promise about commit outcome.
        tx.sql.commit()
        TeachActivationResult("activated", TeachActiveSkill(claim.revisionId, next.toString()))
    }
}
