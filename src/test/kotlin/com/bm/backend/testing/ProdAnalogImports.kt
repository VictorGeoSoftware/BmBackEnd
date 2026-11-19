package com.bm.backend.testing

import com.bm.backend.models.TeachDraftPreviewRow
import com.bm.backend.models.TeachProposalImportAttempt
import kotlinx.serialization.json.Json
import java.util.UUID

internal class ProdAnalogImports(private val writer: ProdAnalogTransaction, private val reader: ProdAnalogTransaction,
    val binding: ProdAnalogBinding, private val afterRow: (Int) -> Unit, private val afterCommit: () -> Unit,
    private val beforeReadReturn: () -> Unit,
) {
    fun verify() {
        if (writer.binding != binding || reader.binding != binding) throw ProdAnalogUnavailable()
        writer.bounded { query("SELECT id, actor, attempt, fingerprint, ordinal, revision, digest FROM analog.batch WHERE false") { check(!it.next()) } }
        reader.bounded { batches(UUID.randomUUID(), null) }
    }

    fun replay(attempt: TeachProposalImportAttempt, remaining: () -> Long, checkpoint: () -> Unit) =
        writer.use(remaining, checkpoint) { prior(attempt) }

    private fun TeachProposalJdbcStore.Tx.prior(attempt: TeachProposalImportAttempt) = query(
        "SELECT id, fingerprint, revision, ordinal, digest FROM analog.batch WHERE actor = ? AND attempt = ?",
        UUID.fromString(attempt.actor), attempt.id,
    ) { result ->
        if (!result.next()) null else {
            if (result.getString(2) != attempt.fingerprint) throw ProdAnalogConflict("attempt_mismatch")
            ProdAnalogReceipt("alreadyStored", PROD_ANALOG, result.getString(1), result.getString(3), result.getString(4), result.getString(5), 3)
        }
    }

    fun record(attempt: TeachProposalImportAttempt, pending: ProdAnalogPending,
               remaining: () -> Long, checkpoint: () -> Unit, sameActor: () -> Unit) = writer.use(remaining, checkpoint) {
        sameActor()
        prior(attempt)?.let { return@use it }
        val release = pending.release
        val actor = UUID.fromString(attempt.actor)
        if (release.binding != binding || pending.rows.size != 3) throw ProdAnalogUnavailable()
        val id = query("""INSERT INTO analog.batch (actor, attempt, fingerprint, store, supplier, ordinal, revision, digest)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (actor, attempt) DO NOTHING RETURNING id""",
            actor, attempt.id, attempt.fingerprint, binding.store, release.supplier, release.ordinal, release.revision, release.digest,
        ) { if (it.next()) it.getLong(1) else null } ?: return@use prior(attempt) ?: throw ProdAnalogUnavailable()
        pending.rows.forEachIndexed { index, row ->
            update("INSERT INTO analog.proposal (batch_id, actor, workbook_row, result) VALUES (?, ?, ?, ?::jsonb)",
                id, actor, index + 16, Json.encodeToString(TeachDraftPreviewRow.serializer(), row))
            afterRow(index + 16)
        }
        sameActor()
        commit()
        afterCommit()
        ProdAnalogReceipt("stored", PROD_ANALOG, id.toString(), release.revision, release.ordinal.toString(), release.digest, 3)
    }

    private fun TeachProposalJdbcStore.Tx.batches(actor: UUID, before: Long?) = query("""SELECT id, supplier, revision, ordinal, digest, imported_at
        FROM analog.batch WHERE actor = ? ${if (before != null) "AND id < ?" else ""} ORDER BY id DESC LIMIT 5""",
        *listOfNotNull(actor, before).toTypedArray()) { result ->
        buildList {
            while (result.next()) add(ProdAnalogBatch(result.getString(1), result.getString(2), result.getString(3),
                result.getString(4), result.getString(5), result.getTimestamp(6).toInstant().toString(), emptyList()))
        }
    }

    fun list(actor: UUID, before: Long?, remaining: () -> Long, checkpoint: () -> Unit): ProdAnalogPage {
        val page = reader.use(remaining, checkpoint) {
            val all = batches(actor, before)
            val selected = all.take(4).map { batch ->
                val rows = query("""SELECT result FROM analog.proposal WHERE actor = ? AND batch_id = ? ORDER BY workbook_row""",
                    actor, batch.importId.toLong()) { result -> buildList {
                        while (result.next()) add(Json.decodeFromString<TeachDraftPreviewRow>(result.getString(1)))
                    } }
                if (rows.size != 3) throw ProdAnalogUnavailable()
                batch.copy(proposals = rows)
            }
            ProdAnalogPage("imports", PROD_ANALOG, selected, selected.lastOrNull()?.importId?.takeIf { all.size > selected.size })
        }
        beforeReadReturn()
        return page
    }
}
