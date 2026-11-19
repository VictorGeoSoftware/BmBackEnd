package com.bm.backend.services

import com.bm.backend.models.TeachDraftPreviewInspection
import com.bm.backend.models.TeachDraftPreviewRow
import com.bm.backend.models.TeachProposalImportAttempt
import com.bm.backend.models.TeachProposalImportPending
import com.bm.backend.models.TeachRegisteredSkillRevision
import com.bm.backend.repositories.ports.TeachProposalImportStore
import com.bm.backend.repositories.ports.TeachProposalStoreUnavailable
import java.math.BigDecimal
import java.security.MessageDigest

internal val teachProposalAttemptId = Regex("[0-9a-f]{32}")
internal const val TEACH_IMPORT_LIST_PAGE_SIZE = 4

internal class TeachProposalImportService private constructor(
    private val selector: TeachSupplierSkillSelectionService,
    private val store: TeachProposalImportStore,
    private val apply: (TeachRegisteredSkillRevision, TeachDraftPreviewInspection, () -> Unit) -> List<TeachDraftPreviewRow>,
) {
    companion object {
        fun mount(selector: TeachSupplierSkillSelectionService, store: TeachProposalImportStore,
                  apply: (TeachRegisteredSkillRevision, TeachDraftPreviewInspection, () -> Unit) -> List<TeachDraftPreviewRow> =
                      { revision, inspection, checkpoint -> applyTeachPriceRule(revision.rule, inspection, checkpoint) }): TeachProposalImportService? {
            val id = store.identity
            if (!teachFixtureId.matches(id.storeId) || id.storeId == selector.catalogId || id.catalogId != selector.catalogId ||
                id.manifest != selector.manifest || id.environment != "qa" || id.schemaVersion !in 1..2) return null
            return try { store.verify(); TeachProposalImportService(selector, store, apply) }
            catch (_: TeachProposalStoreUnavailable) { null }
        }
    }

    fun attempt(actor: String, id: String, filename: String, bytes: ByteArray, size: Int): TeachProposalImportAttempt {
        val hash = MessageDigest.getInstance("SHA-256")
        hash.update(teachTuple(listOf("bm.teach.import/v1", filename, size.toString())))
        hash.update(bytes, 0, size)
        return TeachProposalImportAttempt(actor, id, sha256Hex(hash.digest()))
    }

    fun prepare(attempt: TeachProposalImportAttempt, filename: String, bytes: ByteArray, size: Int,
                validator: TeachWorkbookValidator, inspector: TeachWorkbookInspector,
                remaining: () -> Long, checkpoint: () -> Unit): TeachProposalImportPending? {
        if (store.exists(attempt, remaining, checkpoint)) return null
        val selected = selector.select(filename, validator, inspector, bytes, size, remaining, checkpoint)
        val shape = inspector.inspectForProposalImport(validator, bytes, size, checkpoint)
        requireImportShape(shape)
        val rows = apply(selected.revision, shape, checkpoint).map { row ->
            row.copy(prices = row.prices.map { price ->
                price.copy(decimal = BigDecimal(price.decimal).stripTrailingZeros().toPlainString())
            })
        }
        checkpoint()
        return TeachProposalImportPending(selected, rows)
    }

    fun record(attempt: TeachProposalImportAttempt, pending: TeachProposalImportPending?, remaining: () -> Long,
               checkpoint: () -> Unit) = store.record(attempt, pending, remaining, checkpoint)

    fun list(actor: String, before: Long?, remaining: () -> Long, checkpoint: () -> Unit) =
        store.list(actor, before, remaining, checkpoint)

    val identity get() = store.identity
    fun verifyValidationProjection() = store.verifyValidationProjection()
    fun listWithValidation(actor: String, before: Long?, remaining: () -> Long, checkpoint: () -> Unit) =
        store.listWithValidation(actor, before, remaining, checkpoint)
}

// Approved synthetic table coordinates, not its values or a hash of the incoming workbook.
private val syntheticColumns = mapOf(
    14 to ('C'..'Z').map(Char::toString).joinToString(" ") + " AA AB AC AD AE",
    15 to "C D E F G H I J K Q R S T Y Z AA AC AD",
    16 to "C D E F G H I J K Q S T Y Z AA AC AD",
    17 to "C D E F G H I J K Q S T Y Z AA AC AD",
    18 to "C D E F G H I J K Q S T Y Z AA AC AD",
    19 to "C D E F G H I J K L M N O P Q S T U V W X Y Z AA AC AD",
    20 to "C D E F G H I J Q R S T Y Z AB AC AD",
).mapValues { (row, columns) -> columns.split(' ').map { "$it$row" }.toSet() }

private val optionalBlanks = (16..18).flatMap { listOf("L$it", "M$it") }.toSet()

private fun requireImportShape(shape: TeachDraftPreviewInspection) {
    previewCheck(shape.rowVisibility == (14..20).associateWith { it != 15 && it != 20 })
    val cells = shape.sheet.cells.associateBy { it.a1 }
    val required = syntheticColumns.values.flatten().toSet()
    previewCheck(cells.keys.containsAll(required) && cells.keys.minus(required).all {
        it in optionalBlanks && cells.getValue(it).kind == "blank"
    })
    previewCheck(cells.keys.all { it in shape.defaultStyleCells } &&
        shape.visibleCells == cells.keys - syntheticColumns.getValue(15) - syntheticColumns.getValue(20))
    previewCheck(cells["F19"]?.let { it.kind == "text" && it.value?.content == "3.0TD" } == true &&
        cells["G19"]?.let { it.kind == "text" && it.value?.content == "Precio por Periodos" } == true)
}
