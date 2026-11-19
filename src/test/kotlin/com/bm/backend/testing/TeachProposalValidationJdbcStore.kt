package com.bm.backend.testing

import com.bm.backend.models.TeachProposalImportIdentity
import com.bm.backend.models.TeachProposalValidationResult
import com.bm.backend.repositories.ports.TeachProposalStoreUnavailable
import com.bm.backend.repositories.ports.TeachProposalValidationStore
import com.bm.backend.repositories.ports.TeachProposalValidationDeadline
import com.bm.backend.services.teachRevisionId
import java.sql.Connection
import java.sql.SQLException
import java.util.UUID
import java.util.concurrent.Executor

/** Fixture-only writer. It receives neither import roles nor a catalog datasource. */
internal class TeachProposalValidationJdbcStore(
    override val identity: TeachProposalImportIdentity,
    private val database: String,
    private val writer: String,
    private val connect: (() -> Long, () -> Unit) -> Connection,
    private val beforeInsert: () -> Unit,
    private val beforeCommit: () -> Unit,
    private val afterCommit: () -> Unit,
) : TeachProposalValidationStore {
    override fun verify() {
        val start = System.nanoTime()
        val remaining = { (5_000 - (System.nanoTime() - start) / 1_000_000).also { if (it <= 0) unavailable() } }
        tx(remaining, { remaining() }) {
            query("SELECT validation_ref, actor, validated_at FROM proposals.validation WHERE false") { check(!it.next()) }
            val insert = query("""SELECT has_column_privilege(current_user, 'proposals.validation', 'validation_ref', 'INSERT'),
                has_column_privilege(current_user, 'proposals.validation', 'actor', 'INSERT')""") {
                it.next() && it.getBoolean(1) && it.getBoolean(2)
            }
            if (!insert) unavailable()
        }
    }

    override fun confirm(actor: String, ref: UUID, remaining: () -> Long, checkpoint: () -> Unit): TeachProposalValidationResult? =
        tx(remaining, checkpoint) {
            // A plain SELECT cannot wait for an uncommitted batch to become visible.
            val target = query("""SELECT id, revision, generation, environment, manifest FROM proposals.batch
                WHERE validation_ref = ? AND actor = ?""", ref, actor) { rs ->
                if (!rs.next()) null else {
                    val id = rs.getLong(1)
                    val revision = rs.getString(2)
                    val generation = rs.getLong(3)
                    if (id <= 0 || !teachRevisionId.matches(revision) || generation <= 0 || rs.getString(4) != "qa" ||
                        rs.getString(5) != identity.manifest) unavailable()
                    Triple(id.toString(), revision, generation.toString())
                }
            } ?: return@tx null
            beforeInsert()
            val inserted = query("""INSERT INTO proposals.validation (validation_ref, actor)
                SELECT validation_ref, actor FROM proposals.batch WHERE validation_ref = ? AND actor = ?
                ON CONFLICT (validation_ref) DO NOTHING RETURNING validated_at""", ref, actor) {
                if (it.next()) it.getTimestamp(1).toInstant().toString() else null
            }
            // READ COMMITTED starts a fresh snapshot after any uniqueness wait, unlike the INSERT snapshot.
            val at = inserted ?: query("""SELECT validated_at FROM proposals.validation
                WHERE validation_ref = ? AND actor = ?""", ref, actor) {
                if (it.next()) it.getTimestamp(1).toInstant().toString() else unavailable()
            }
            if (inserted != null) {
                beforeCommit()
                commit()
                afterCommit()
            }
            TeachProposalValidationResult(if (inserted == null) "alreadyValidated" else "validated",
                target.first, target.second, target.third, at)
        }

    private fun <T> tx(remaining: () -> Long, checkpoint: () -> Unit, run: TeachProposalJdbcStore.Tx.() -> T): T {
        try {
            checkpoint()
            return connect(remaining, checkpoint).use { sql ->
                sql.setNetworkTimeout(direct, remaining().toInt())
                sql.transactionIsolation = Connection.TRANSACTION_READ_COMMITTED
                sql.autoCommit = false
                sql.exec("SET LOCAL client_connection_check_interval = '100ms'")
                val tx = TeachProposalJdbcStore.Tx(sql, remaining, checkpoint)
                val valid = tx.query("""SELECT current_database(), current_user, session_user, id, catalog, manifest, environment,
                    schema_version, current_setting('transaction_read_only') FROM proposals.identity""") {
                    it.next() && it.getString(1) == database && it.getString(2) == writer && it.getString(3) == writer &&
                        it.getString(4) == identity.storeId && it.getString(5) == identity.catalogId && it.getString(6) == identity.manifest &&
                        it.getString(7) == identity.environment && it.getInt(8) == identity.schemaVersion && it.getString(9) == "off" && !it.next()
                }
                if (!valid) unavailable()
                tx.run().also { checkpoint() }
                // JDBC connection closure discards uncommitted work, including on cancellation.
            }
        } catch (failure: SQLException) {
            checkpoint()
            if (failure.sqlState in setOf("55P03", "57014")) throw TeachProposalValidationDeadline()
            unavailable()
        }
    }

    private companion object {
        val direct = Executor { it.run() }
        fun unavailable(): Nothing = throw TeachProposalStoreUnavailable()
    }
}
