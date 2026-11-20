package com.bm.backend.testing

import com.bm.backend.models.*
import com.bm.backend.repositories.ports.*
import com.bm.backend.services.teachRevisionId
import java.sql.Connection
import java.sql.SQLException
import java.util.UUID
import java.util.concurrent.Executor

internal class TeachProductionPromotionJdbcStore(
    override val identity: TeachProposalImportIdentity,
    override val providerId: UUID,
    private val database: String, private val writer: String, private val reader: String,
    private val connect: (Boolean, () -> Long, () -> Unit) -> Connection,
    private val beforeReserveCommit: () -> Unit, private val afterReserveCommit: () -> Unit,
    private val beforeFinalizeCommit: () -> Unit, private val afterFinalizeCommit: () -> Unit,
    private val afterRead: () -> Unit, private val beforeOpenedReplay: () -> Unit,
) : TeachPromotionStore {
    override fun verify() {
        val start = System.nanoTime()
        val remaining = { (5000 - (System.nanoTime() - start) / 1_000_000).also { if (it <= 0) unavailable() } }
        for (write in listOf(false, true)) tx(write, remaining, { remaining() }) {
            snapshot("synthetic-absent", UUID(0, 0))
            val access = query("""SELECT has_column_privilege(current_user, 'proposals.promotion', 'digest', 'INSERT'),
                has_column_privilege(current_user, 'proposals.promotion', 'proposal_id', 'UPDATE')""") {
                it.next() && it.getBoolean(1) == write && it.getBoolean(2) == write
            }
            if (!access) unavailable()
        }
    }

    override fun read(actor: String, ref: UUID, remaining: () -> Long, checkpoint: () -> Unit) =
        tx(false, remaining, checkpoint) { snapshot(actor, ref).also { afterRead() } }

    override fun reserve(actor: String, ref: UUID, evidence: TeachPromotionEvidence, digest: String,
        remaining: () -> Long, checkpoint: () -> Unit) = tx(true, remaining, checkpoint) {
        val target = snapshot(actor, ref) ?: return@tx null
        if (target.evidence != evidence) unavailable()
        target.reservation?.let {
            if (it.proposalId != null) beforeOpenedReplay()
            return@tx it
        }
        val inserted = query("""INSERT INTO proposals.promotion (validation_ref, actor, digest)
            SELECT validation_ref, actor, ? FROM proposals.validation
            WHERE validation_ref = ? AND actor = ? AND result = 'expected_prices_confirmed'
            ON CONFLICT (validation_ref, destination) DO NOTHING RETURNING op_key""", digest, ref, actor) {
            if (it.next()) it.getObject(1, UUID::class.java) else null
        }
        // A fresh READ COMMITTED statement sees the winner after a uniqueness wait.
        val row = snapshot(actor, ref)?.takeIf { it.evidence == evidence }?.reservation ?: unavailable()
        if (inserted != null) {
            if (inserted != row.key || row.digest != digest) unavailable()
            beforeReserveCommit()
            commit()
            afterReserveCommit()
        }
        row
    }

    override fun finalize(actor: String, ref: UUID, reservation: TeachPromotionReservation, id: Long,
        remaining: () -> Long, checkpoint: () -> Unit) = tx(true, remaining, checkpoint) {
        val inserted = query("""UPDATE proposals.promotion SET proposal_id = ?, opened_at = clock_timestamp()
            WHERE validation_ref = ? AND actor = ? AND destination = 'bm-pilot/skills-fixture' AND op_key = ?
              AND digest = ? AND proposal_id IS NULL AND opened_at IS NULL RETURNING proposal_id""",
            id, ref, actor, reservation.key, reservation.digest) { it.next() }
        val row = snapshot(actor, ref)?.reservation ?: unavailable()
        if (row.key != reservation.key || row.digest != reservation.digest || row.proposalId != id || row.openedAt == null) unavailable()
        if (inserted) {
            beforeFinalizeCommit()
            commit()
            afterFinalizeCommit()
        }
        row to inserted
    }

    private fun TeachProposalJdbcStore.Tx.snapshot(actor: String, ref: UUID): TeachPromotionSnapshot? = query("""
        SELECT b.id, b.supplier, b.revision, b.generation, b.manifest, v.validated_at, p.op_key, p.digest, p.proposal_id, p.opened_at
        FROM proposals.validation v JOIN proposals.batch b ON b.validation_ref = v.validation_ref AND b.actor = v.actor
        LEFT JOIN proposals.promotion p ON p.validation_ref = v.validation_ref AND p.actor = v.actor AND p.destination = 'bm-pilot/skills-fixture'
        WHERE v.validation_ref = ? AND v.actor = ? AND v.result = 'expected_prices_confirmed'
          AND b.environment = 'qa' AND b.generation > 0 AND b.supplier = 'gesternova' AND b.manifest = ?""", ref, actor, identity.manifest) {
        if (!it.next()) null else {
            val evidence = TeachPromotionEvidence(it.getString(1), it.getString(2), it.getString(3), it.getString(4),
                it.getString(5), it.getTimestamp(6).toInstant().toString())
            val row = it.getObject(7, UUID::class.java)?.let { key ->
                val digest = it.getString(8)
                val id = it.getObject(9, java.lang.Long::class.java)?.toLong()
                val at = it.getTimestamp(10)?.toInstant()?.toString()
                if (!teachRevisionId.matches(digest) || (id == null) != (at == null) || (id != null && id <= 0)) unavailable()
                TeachPromotionReservation(key, digest, id, at)
            }
            if (!teachRevisionId.matches(evidence.revision) || it.next()) unavailable()
            TeachPromotionSnapshot(evidence, row)
        }
    }

    private fun <T> tx(write: Boolean, remaining: () -> Long, checkpoint: () -> Unit, run: TeachProposalJdbcStore.Tx.() -> T): T {
        try {
            checkpoint()
            return connect(write, remaining, checkpoint).use { sql ->
                sql.setNetworkTimeout(direct, remaining().toInt())
                sql.transactionIsolation = if (write) Connection.TRANSACTION_READ_COMMITTED else Connection.TRANSACTION_REPEATABLE_READ
                sql.isReadOnly = !write
                sql.autoCommit = false
                val tx = TeachProposalJdbcStore.Tx(sql, remaining, checkpoint)
                tx.query("SELECT set_config('client_connection_check_interval', '100ms', true)") { if (!it.next()) unavailable() }
                val role = if (write) writer else reader
                val valid = tx.query("""SELECT current_database(), current_user, session_user, id, catalog, manifest, environment,
                    schema_version, current_setting('transaction_read_only'), obj_description('proposals.promotion'::regclass, 'pg_class'),
                    (SELECT string_agg(attname || ':' || format_type(atttypid, atttypmod) || ':' || attnotnull, ',' ORDER BY attnum)
                      FROM pg_attribute WHERE attrelid = 'proposals.promotion'::regclass AND attnum > 0 AND NOT attisdropped),
                    (SELECT count(*) FROM pg_constraint WHERE conrelid = 'proposals.promotion'::regclass AND convalidated
                      AND conname IN ('promotion_pkey', 'promotion_op_key_key', 'promotion_proposal_id_key', 'promotion_completion',
                        'promotion_digest', 'promotion_destination', 'promotion_event_fk')),
                    (SELECT string_agg(pg_get_constraintdef(oid), ';' ORDER BY conname) FROM pg_constraint
                      WHERE conrelid = 'proposals.promotion'::regclass AND contype IN ('f','p','u')),
                    (SELECT string_agg(pg_get_expr(adbin, adrelid), ';' ORDER BY adnum) FROM pg_attrdef
                      WHERE adrelid = 'proposals.promotion'::regclass) FROM proposals.identity""") {
                    it.next() && it.getString(1) == database && it.getString(2) == role && it.getString(3) == role &&
                        it.getString(4) == identity.storeId && it.getString(5) == identity.catalogId && it.getString(6) == identity.manifest &&
                        it.getString(7) == "qa" && identity.environment == "qa" && it.getInt(8) == 2 && identity.schemaVersion == 2 &&
                        it.getString(9) == (if (write) "off" else "on") && it.getString(10) == "bm.teach.promotion/v1:$providerId" &&
                        it.getString(11) == shape && it.getInt(12) == 7 && it.getString(13) == keys &&
                        it.getString(14) == "'bm-pilot/skills-fixture'::text;gen_random_uuid();clock_timestamp()" && !it.next()
                }
                if (!valid) unavailable()
                tx.run().also { checkpoint() }
            }
        } catch (failure: SQLException) {
            checkpoint()
            if (failure.sqlState in setOf("55P03", "57014")) throw TeachProposalValidationDeadline()
            unavailable()
        }
    }

    private companion object {
        val direct = Executor { it.run() }
        const val shape = "validation_ref:uuid:true,actor:text:true,destination:text:true,op_key:uuid:true,digest:text:true," +
            "requested_at:timestamp with time zone:true,proposal_id:bigint:false,opened_at:timestamp with time zone:false"
        const val keys = "FOREIGN KEY (validation_ref, actor) REFERENCES proposals.validation(validation_ref, actor);" +
            "UNIQUE (op_key);PRIMARY KEY (validation_ref, destination);UNIQUE (proposal_id)"
        fun unavailable(): Nothing = throw TeachProposalStoreUnavailable()
    }
}
