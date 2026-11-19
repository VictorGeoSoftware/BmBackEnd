package com.bm.backend.testing

import com.bm.backend.models.TeachActiveSkill
import com.bm.backend.repositories.ports.TeachActiveSkillReader
import com.bm.backend.repositories.ports.TeachSkillCatalogUnavailable
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.util.concurrent.Executor

/** No writer, audit, owner, or sentinel capability. The connection factory belongs to the test fixture only. */
internal class TeachActiveSkillJdbcReader(
    override val storeId: String,
    private val database: String,
    private val role: String,
    private val connect: (() -> Long, () -> Unit) -> Connection,
) : TeachActiveSkillReader {
    private class Snapshot(val sql: Connection, val remainingMillis: () -> Long) {
        fun <T> query(query: String, supplier: String? = null, read: (ResultSet) -> T): T {
            val ms = remainingMillis().toInt().coerceAtLeast(1)
            sql.setNetworkTimeout(Executor { it.run() }, ms)
            sql.exec("SET LOCAL statement_timeout = $ms; SET LOCAL lock_timeout = $ms")
            sql.setNetworkTimeout(Executor { it.run() }, remainingMillis().toInt().coerceAtLeast(1))
            return sql.prepareStatement(query).use { statement ->
                if (supplier != null) statement.setString(1, supplier)
                statement.executeQuery().use(read)
            }
        }
    }

    private fun <T> snapshot(manifest: String, remainingMillis: () -> Long, checkpoint: () -> Unit, run: (Snapshot) -> T): T {
        try {
            remainingMillis()
            return connect(remainingMillis, checkpoint).use { sql ->
                sql.setNetworkTimeout(Executor { it.run() }, remainingMillis().toInt().coerceAtLeast(1))
                // Set characteristics before the first SQL statement of the transaction.
                sql.isReadOnly = true
                sql.transactionIsolation = Connection.TRANSACTION_REPEATABLE_READ
                sql.autoCommit = false
                // Let PostgreSQL notice a disconnected client even during a relation lock wait.
                sql.exec("SET LOCAL client_connection_check_interval = '100ms'")
                val tx = Snapshot(sql, remainingMillis)
                val valid = tx.query("""SELECT current_database(), current_user, id, manifest,
                    current_setting('transaction_read_only'), current_setting('transaction_isolation') FROM pilot.identity""") { rows ->
                    rows.next() && rows.getString(1) == database && rows.getString(2) == role &&
                        rows.getString(3) == storeId && rows.getString(4) == manifest && rows.getString(5) == "on" &&
                        rows.getString(6) == "repeatable read" && !rows.next()
                }
                if (!valid) throw TeachSkillCatalogUnavailable()
                run(tx).also { checkpoint() }
                // Close (no commit); the server discards the read-only transaction.
            }
        } catch (_: SQLException) {
            remainingMillis() // A deadline or peer cancellation takes precedence over a catalog error.
            throw TeachSkillCatalogUnavailable()
        }
    }

    override fun verify(manifest: String): Map<String, TeachActiveSkill> {
        val start = System.nanoTime()
        val remaining = {
            (5_000 - (System.nanoTime() - start) / 1_000_000).also { if (it <= 0) throw TeachSkillCatalogUnavailable() }
        }
        return snapshot(manifest, remaining, { remaining() }) { tx ->
            tx.query("SELECT supplier, revision, generation FROM pilot.active") { rows ->
                buildMap {
                    while (rows.next()) {
                        remaining()
                        if (put(rows.getString(1) ?: throw TeachSkillCatalogUnavailable(), pointer(rows, 2)) != null) {
                            throw TeachSkillCatalogUnavailable()
                        }
                    }
                }
            }
        }
    }

    override fun read(manifest: String, supplier: String, remainingMillis: () -> Long, checkpoint: () -> Unit): TeachActiveSkill? =
        snapshot(manifest, remainingMillis, checkpoint) { tx ->
            tx.query("SELECT revision, generation FROM pilot.active WHERE supplier = ?", supplier) { rows ->
                if (!rows.next()) null else pointer(rows, 1).also {
                    if (rows.next()) throw TeachSkillCatalogUnavailable()
                }
            }
        }

    private fun pointer(rows: ResultSet, column: Int) = TeachActiveSkill(
        rows.getString(column) ?: throw TeachSkillCatalogUnavailable(),
        rows.getString(column + 1) ?: throw TeachSkillCatalogUnavailable(),
    )
}
