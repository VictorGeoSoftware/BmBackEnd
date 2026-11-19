package com.bm.backend.testing

import com.bm.backend.models.*
import com.bm.backend.repositories.ports.TeachProposalAttemptMismatch
import com.bm.backend.repositories.ports.TeachProposalImportStore
import com.bm.backend.repositories.ports.TeachProposalStoreUnavailable
import com.bm.backend.services.TEACH_IMPORT_LIST_PAGE_SIZE
import kotlinx.serialization.json.*
import java.math.BigDecimal
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.util.concurrent.Executor

/** Fixtures own the connection factories. No datasource, bootstrap, or credentials from the environment. */
internal class TeachProposalJdbcStore(
    override val identity: TeachProposalImportIdentity,
    private val database: String,
    private val writer: String,
    private val reader: String,
    private val writeConnect: (() -> Long, () -> Unit) -> Connection,
    private val readConnect: (() -> Long, () -> Unit) -> Connection,
    private val afterRow: (Int) -> Unit,
    private val beforeClaim: () -> Unit,
    private val afterCommit: () -> Unit,
) : TeachProposalImportStore {
    override fun verify() {
        val start = System.nanoTime()
        val remaining = { (5_000 - (System.nanoTime() - start) / 1_000_000).also { if (it <= 0) unavailable() } }
        tx(true, remaining, { remaining() }) {}
        tx(false, remaining, { remaining() }) {}
    }

    override fun exists(attempt: TeachProposalImportAttempt, remaining: () -> Long, checkpoint: () -> Unit) =
        tx(true, remaining, checkpoint) {
            query("SELECT id FROM proposals.batch WHERE actor = ? AND attempt = ?", attempt.actor, attempt.id) { it.next() }
        }

    override fun record(attempt: TeachProposalImportAttempt, pending: TeachProposalImportPending?, remaining: () -> Long,
                        checkpoint: () -> Unit): TeachProposalImportReceipt {
        val values = pending?.rows?.map { row ->
            row.prices.map { price ->
                BigDecimal(price.decimal).also {
                    check(it.signum() >= 0 && it.precision() <= 18 && it.scale() in 0..12 && it.precision() - it.scale() <= 6)
                }
            }.also { check(it.size == 3) }
        }
        return tx(true, remaining, checkpoint) {
            fun replay(): TeachProposalImportReceipt? = query("""SELECT id, revision, generation, digest FROM proposals.batch
                WHERE actor = ? AND attempt = ?""", attempt.actor, attempt.id) { rs ->
                if (!rs.next()) null else {
                    if (rs.getString(4) != attempt.fingerprint) throw TeachProposalAttemptMismatch()
                    TeachProposalImportReceipt("alreadyStored", rs.getString(1), rs.getString(2), rs.getString(3), 3)
                }
            }
            replay()?.let { return@tx it }
            if (pending == null) unavailable()
            check(pending.rows.size == 3)
            val selected = pending.selection
            val revision = selected.revision
            check(revision.manifest == identity.manifest)
            beforeClaim()
            val id = query("""INSERT INTO proposals.batch (actor, attempt, digest, environment, supplier, revision, generation, manifest, source)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (actor, attempt) DO NOTHING RETURNING id""",
                attempt.actor, attempt.id, attempt.fingerprint, identity.environment, revision.supplierKey,
                revision.revisionId, selected.generation.toLong(), revision.manifest, selected.source.wire) { rs ->
                if (rs.next()) rs.getLong(1) else null
            } ?: return@tx replay() ?: unavailable()

            pending.rows.forEachIndexed { index, row ->
                val metadata = Json.encodeToJsonElement(TeachDraftPreviewRow.serializer(), row).jsonObject
                val provenance = JsonObject(metadata + ("prices" to JsonArray(metadata.getValue("prices").jsonArray.map {
                    JsonObject(it.jsonObject - "decimal")
                })))
                val (energy, power1, power2) = checkNotNull(values)[index]
                update("INSERT INTO proposals.proposal VALUES (?, ?, ?::jsonb, ?, ?, ?)",
                    id, index + 16, provenance.toString(), energy, power1, power2)
                afterRow(index + 16)
            }
            commit()
            afterCommit()
            TeachProposalImportReceipt("stored", id.toString(), revision.revisionId, selected.generation, 3)
        }
    }

    override fun verifyValidationProjection() {
        val start = System.nanoTime()
        val remaining = { (5_000 - (System.nanoTime() - start) / 1_000_000).also { if (it <= 0) unavailable() } }
        tx(false, remaining, { remaining() }) {
            query("""SELECT b.validation_ref, v.validated_at FROM proposals.batch b LEFT JOIN proposals.validation v
                ON v.validation_ref = b.validation_ref AND v.actor = b.actor WHERE false""") { check(!it.next()) }
        }
    }

    override fun list(actor: String, before: Long?, remaining: () -> Long, checkpoint: () -> Unit): TeachProposalImportPage {
        val (rows, cursor) = listSnapshot(actor, before, false, remaining, checkpoint)
        return TeachProposalImportPage("imports", rows.map { it.first }, cursor)
    }

    override fun listWithValidation(actor: String, before: Long?, remaining: () -> Long, checkpoint: () -> Unit): TeachValidatedImportPage {
        val (rows, cursor) = listSnapshot(actor, before, true, remaining, checkpoint)
        return TeachValidatedImportPage("imports", rows.map { (batch, ref, evidence) ->
            TeachValidatedImportBatch(batch, checkNotNull(ref), evidence)
        }, cursor)
    }

    private fun listSnapshot(actor: String, before: Long?, enriched: Boolean, remaining: () -> Long, checkpoint: () -> Unit) =
        tx(false, remaining, checkpoint) {
        val batches = query("""SELECT b.id, b.supplier, b.revision, b.generation, b.imported_at
            ${if (enriched) ", b.validation_ref, v.validated_at" else ""} FROM proposals.batch b
            ${if (enriched) "LEFT JOIN proposals.validation v ON v.validation_ref = b.validation_ref AND v.actor = b.actor" else ""}
            WHERE b.actor = ? ${if (before != null) "AND b.id < ?" else ""} ORDER BY b.id DESC LIMIT 5""",
            *listOfNotNull(actor, before).toTypedArray()) { rs ->
            buildList {
                while (rs.next()) add(Triple(TeachProposalImportBatch(rs.getString(1), rs.getString(2), rs.getString(3),
                    rs.getString(4), rs.getTimestamp(5).toInstant().toString(), emptyList()),
                    if (enriched) rs.getString(6) else null,
                    if (enriched) rs.getTimestamp(7)?.toInstant()?.toString()?.let { TeachProposalValidationEvidence("validated", it) } else null))
            }
        }
        val page = batches.take(TEACH_IMPORT_LIST_PAGE_SIZE).map { (batch, ref, evidence) ->
            val proposals = query("""SELECT p.provenance, p.energy, p.power1, p.power2 FROM proposals.proposal p
                JOIN proposals.batch b ON b.id = p.batch_id WHERE b.actor = ? AND b.id = ? ORDER BY p.workbook_row""",
                actor, batch.importId.toLong()) { rs ->
                buildList {
                    while (rs.next()) {
                        val metadata = Json.parseToJsonElement(rs.getString(1)).jsonObject
                        val prices = JsonArray(metadata.getValue("prices").jsonArray.mapIndexed { i, price ->
                            JsonObject(price.jsonObject + ("decimal" to JsonPrimitive(rs.getBigDecimal(i + 2).stripTrailingZeros().toPlainString())))
                        })
                        add(Json.decodeFromJsonElement(TeachDraftPreviewRow.serializer(), JsonObject(metadata + ("prices" to prices))))
                    }
                }
            }
            if (proposals.size != 3) unavailable()
            Triple(batch.copy(proposals = proposals), ref, evidence)
        }
        page to page.lastOrNull()?.first?.importId?.takeIf { batches.size > page.size }
    }

    private fun <T> tx(writable: Boolean, remaining: () -> Long, checkpoint: () -> Unit, run: Tx.() -> T): T {
        try {
            checkpoint()
            val connect = if (writable) writeConnect else readConnect
            return connect(remaining, checkpoint).use { sql ->
                sql.setNetworkTimeout(direct, remaining().toInt())
                sql.isReadOnly = !writable
                sql.transactionIsolation = if (writable) Connection.TRANSACTION_READ_COMMITTED else Connection.TRANSACTION_REPEATABLE_READ
                sql.autoCommit = false
                sql.exec("SET LOCAL client_connection_check_interval = '100ms'")
                val tx = Tx(sql, remaining, checkpoint)
                val valid = tx.query("""SELECT current_database(), current_user, id, catalog, manifest, environment, schema_version,
                    current_setting('transaction_read_only') FROM proposals.identity""") { rs ->
                    rs.next() && rs.getString(1) == database && rs.getString(2) == (if (writable) writer else reader) &&
                        rs.getString(3) == identity.storeId && rs.getString(4) == identity.catalogId &&
                        rs.getString(5) == identity.manifest && rs.getString(6) == identity.environment &&
                        rs.getInt(7) == identity.schemaVersion && rs.getString(8) == (if (writable) "off" else "on") && !rs.next()
                }
                if (!valid) unavailable()
                tx.run().also { checkpoint() }
                // Connection closure discards any uncommitted transaction before admission releases.
            }
        } catch (_: SQLException) {
            checkpoint()
            unavailable()
        }
    }

    internal class Tx(private val sql: Connection, private val remaining: () -> Long, private val checkpoint: () -> Unit) {
        private fun bounded() {
            checkpoint()
            val ms = remaining().toInt()
            sql.setNetworkTimeout(direct, ms)
            sql.exec("SET LOCAL statement_timeout = $ms; SET LOCAL lock_timeout = $ms")
            sql.setNetworkTimeout(direct, remaining().toInt())
        }

        private fun <T> statement(query: String, args: Array<out Any>, run: (PreparedStatement) -> T): T {
            bounded()
            return sql.prepareStatement(query).use { ps ->
                args.forEachIndexed { i, arg -> ps.setObject(i + 1, arg) }
                run(ps)
            }
        }

        fun <T> query(query: String, vararg args: Any, read: (ResultSet) -> T) = statement(query, args) { it.executeQuery().use(read) }
        fun update(query: String, vararg args: Any) = statement(query, args) { check(it.executeUpdate() == 1) }
        fun commit() { bounded(); checkpoint(); sql.commit() }
    }

    companion object {
        private val direct = Executor { it.run() }
        private fun unavailable(): Nothing = throw TeachProposalStoreUnavailable()
    }
}
