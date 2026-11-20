package com.bm.backend.testing

import com.bm.backend.models.TeachProposalImportAttempt
import com.bm.backend.services.*
import java.math.BigDecimal
import java.security.MessageDigest
import java.util.UUID

/** Local analogue only: independent resolver and import ports, no QA or promotion provider. */
internal class ProdAnalogImportService(
    val binding: ProdAnalogBinding,
    private val catalog: ProdAnalogCatalog,
    private val releases: ProdAnalogReleaseReader,
    private val imports: ProdAnalogImports,
    inputs: List<ByteArray>,
    private val afterPin: (ProdAnalogRelease) -> Unit = {},
) {
    private val allow = inputs.map { digest(it, it.size) }.toSet()
    private val validator = TeachWorkbookValidator()
    private val inspector = TeachWorkbookInspector()

    init {
        if (releases.binding != binding || imports.binding != binding) throw ProdAnalogUnavailable()
        releases.verify(); imports.verify()
    }

    fun ingest(actor: UUID, id: String, filename: String, bytes: ByteArray, size: Int,
               remaining: () -> Long, checkpoint: () -> Unit, sameActor: () -> Unit): ProdAnalogReceipt {
        if (digest(bytes, size) !in allow) throw ProdAnalogConflict("incompatible_workbook")
        val hash = MessageDigest.getInstance("SHA-256")
        hash.update(teachTuple(listOf("bm.teach.prod-analog-import/v1", filename, size.toString())))
        hash.update(bytes, 0, size)
        val attempt = TeachProposalImportAttempt(actor.toString(), id, sha256Hex(hash.digest()))
        sameActor()
        imports.replay(attempt, remaining, checkpoint)?.let { return it }
        val (supplier) = identifyTeachSupplier(filename, inspector.inspectSupplierMarkers(validator, bytes, size, checkpoint), checkpoint)
        val release = releases.head(supplier, remaining, checkpoint) ?: throw ProdAnalogConflict("no_released_skill")
        val candidate = catalog.verify(binding, release)
        afterPin(release)
        val shape = inspector.inspectForProposalImport(validator, bytes, size, checkpoint)
        requireImportShape(shape)
        val rows = applyTeachPriceRule(candidate.exact.rule, shape, checkpoint).map { row ->
            row.copy(prices = row.prices.map { price -> price.copy(decimal = BigDecimal(price.decimal).stripTrailingZeros().toPlainString()) })
        }
        checkpoint()
        return imports.record(attempt, ProdAnalogPending(release, rows), remaining, checkpoint, sameActor)
    }

    fun list(actor: UUID, cursor: Long?, remaining: () -> Long, checkpoint: () -> Unit) = imports.list(actor, cursor, remaining, checkpoint)

    private fun digest(bytes: ByteArray, size: Int) = sha256Hex(MessageDigest.getInstance("SHA-256").run {
        update(bytes, 0, size); digest()
    })
}
