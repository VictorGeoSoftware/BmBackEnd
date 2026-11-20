package com.bm.backend.services

import com.bm.backend.models.DraftSupplierSkillV2
import com.bm.backend.models.TeachClarificationCitation
import com.bm.backend.models.TeachDraftPreviewBinding
import com.bm.backend.models.TeachDraftPreviewFrame
import com.bm.backend.models.TeachDraftPreviewHeader
import com.bm.backend.models.TeachDraftPreviewOutput
import com.bm.backend.models.TeachDraftPreviewPrepared
import com.bm.backend.models.TeachDraftPreviewPrice
import com.bm.backend.models.TeachDraftPreviewResult
import com.bm.backend.models.TeachDraftPreviewRow
import com.bm.backend.models.TeachDraftPreviewRule
import com.bm.backend.models.TeachDraftPreviewSelection
import com.bm.backend.models.TeachWorkbookCell
import java.math.BigDecimal
import java.security.MessageDigest
import java.util.HexFormat

internal class TeachDraftPreviewConflict : RuntimeException()
internal fun previewCheck(condition: Boolean) { if (!condition) throw TeachDraftPreviewConflict() }
internal fun sha256Hex(digest: ByteArray): String = HexFormat.of().formatHex(digest)

internal const val GESTERNOVA_PREVIEW_PROFILE = "gesternova-20td-sanitized"
internal const val GESTERNOVA_SUPPLIER = "gesternova"
private const val VERSION = 2
// Approved synthetic fixture pin. No test resource is read at runtime.
internal const val GESTERNOVA_PREVIEW_HASH = "45c27cd6d6c646f182c2b1a4bd8f0863a21d5ffeef6c09df40f644d318cd85b6"
internal val gesternovaPreviewRule = TeachDraftPreviewRule(
    kind = "fixed_period_prices",
    sheet = "Tarifas",
    candidateRows = (15..20).toList(),
    fixedRows = (16..18).toList(),
    tariff = "2.0TD",
    otherTariff = "3.0TD",
    fixedType = "Precio por Periodos",
    excludedTypes = listOf("Indexado"),
    headers = listOf(TeachDraftPreviewHeader("F14", "Tarifa"), TeachDraftPreviewHeader("G14", "Tipo precio"), TeachDraftPreviewHeader("I14", "Producto")),
    bindings = listOf(
        TeachDraftPreviewBinding("energy", "P1", "€/kWh", TeachDraftPreviewHeader("K14", "TEA_P1 €/kWh")),
        TeachDraftPreviewBinding("power", "P1", "€/kWdia", TeachDraftPreviewHeader("S14", "TP_P1 (€/kWdia)")),
        TeachDraftPreviewBinding("power", "P2", "€/kWdia", TeachDraftPreviewHeader("T14", "TP_P2 (€/kWdia)")),
    ),
    absentHeaders = listOf(TeachDraftPreviewHeader("L14", "TEA_P2 €/kWh"), TeachDraftPreviewHeader("M14", "TEA_P3 €/kWh")),
    // The audited styles part has a single cell XF, numFmtId=0 (General), no custom number formats.
    styleSha256 = "ceabc26839ae8c5285c667366bffa538562f76e219eb4f2f5188626232d96900",
    decimalPolicy = "unsigned-plain-6i-12f-no-exponent",
)

/** Fixed-order fields; each decimal byte count + colon precedes exactly that many UTF-8 bytes. */
internal fun previewRevision(digest: String, rule: TeachDraftPreviewRule): String {
    val fields = buildList {
        addAll(listOf("bm.teach.supplier-draft/v2", VERSION.toString(), GESTERNOVA_PREVIEW_PROFILE, GESTERNOVA_SUPPLIER, digest, rule.kind, rule.sheet))
        fun rows(rows: List<Int>) { add(rows.size.toString()); addAll(rows.map(Int::toString)) }
        fun header(header: TeachDraftPreviewHeader) { add(header.a1); add(header.label) }
        rows(rule.candidateRows)
        rows(rule.fixedRows)
        addAll(listOf(rule.tariff, rule.otherTariff, rule.fixedType, rule.excludedTypes.size.toString()))
        addAll(rule.excludedTypes)
        add(rule.headers.size.toString()); rule.headers.forEach(::header)
        add(rule.bindings.size.toString()); rule.bindings.forEach {
            addAll(listOf(it.term, it.period, it.unit)); header(it.header)
        }
        add(rule.absentHeaders.size.toString()); rule.absentHeaders.forEach(::header)
        addAll(listOf(rule.styleSha256, rule.decimalPolicy))
    }
    val hash = MessageDigest.getInstance("SHA-256")
    fields.forEach { field ->
        val bytes = field.encodeToByteArray()
        hash.update("${bytes.size}:".encodeToByteArray())
        hash.update(bytes)
    }
    return sha256Hex(hash.digest())
}

internal class TeachDraftPreviewService(
    private val validator: TeachWorkbookValidator,
    private val inspector: TeachWorkbookInspector,
) {
    fun execute(frame: TeachDraftPreviewFrame, checkpoint: () -> Unit): TeachDraftPreviewResult =
        executePinned(frame, frame.workbookSha256 == GESTERNOVA_PREVIEW_HASH, checkpoint)

    internal fun executePinned(frame: TeachDraftPreviewFrame, knownPin: Boolean, checkpoint: () -> Unit): TeachDraftPreviewResult {
        checkpoint()
        previewCheck(frame.profileKey == GESTERNOVA_PREVIEW_PROFILE)
        if (!knownPin) {
            // Distinguish an invalid XLSX from a different, valid sample; do not inspect its cells.
            validator.validate(frame.workbook, frame.workbook.size, checkpoint)
            throw TeachDraftPreviewConflict()
        }
        val rule = gesternovaPreviewRule
        val draft = DraftSupplierSkillV2(VERSION, GESTERNOVA_PREVIEW_PROFILE, GESTERNOVA_SUPPLIER,
            frame.workbookSha256, rule, previewRevision(frame.workbookSha256, rule))
        frame.selection?.let { previewCheck(it == TeachDraftPreviewSelection(draft.supplierKey, draft.workbookSha256, draft.revisionId)) }
        val rows = extract(frame.workbook, checkpoint)
        checkpoint()
        return if (frame.selection == null) TeachDraftPreviewPrepared(draft) else
            TeachDraftPreviewOutput(draft.profileKey, draft.supplierKey, draft.workbookSha256, draft.revisionId, rows)
    }

    /** Same bounded inspection and semantic checks for preparation and execution; independent of the wire pin. */
    internal fun extract(bytes: ByteArray, checkpoint: () -> Unit): List<TeachDraftPreviewRow> {
        val inspection = inspector.inspectForDraftPreview(validator, bytes, checkpoint)
        return applyTeachPriceRule(gesternovaPreviewRule, inspection, checkpoint)
    }
}

/** Apply a resolved immutable rule to an already inspected table, without consulting the catalogue. */
internal fun applyTeachPriceRule(
    rule: TeachDraftPreviewRule, inspection: com.bm.backend.models.TeachDraftPreviewInspection, checkpoint: () -> Unit,
): List<TeachDraftPreviewRow> {
        previewCheck(inspection.sheet.name == rule.sheet && inspection.stylesSha256 == rule.styleSha256)
        val cells = inspection.sheet.cells.associateBy { it.a1 }
        val headers = rule.headers + rule.bindings.map { it.header } + rule.absentHeaders
        fun cite(ref: String) = TeachClarificationCitation(rule.sheet, ref)
        fun required(ref: String): TeachWorkbookCell {
            previewCheck(ref in inspection.visibleCells && ref in inspection.defaultStyleCells)
            return cells[ref] ?: throw TeachDraftPreviewConflict()
        }
        fun text(ref: String): String {
            val cell = required(ref)
            previewCheck(cell.kind == "text")
            return checkNotNull(cell.value).content
        }
        for (header in headers) previewCheck(text(header.a1) == header.label)
        val (tariffHeader, typeHeader, proposalHeader) = rule.headers
        val included = mutableListOf<Int>()
        val names = mutableSetOf<String>()
        val rows = rule.candidateRows.mapNotNull { row ->
            checkpoint()
            // No hidden discriminator or price is consumed by the rule.
            if (!(inspection.rowVisibility[row] ?: throw TeachDraftPreviewConflict())) return@mapNotNull null
            val tariff = text(tariffHeader.at(row))
            val type = text(typeHeader.at(row))
            previewCheck(tariff == rule.tariff || tariff == rule.otherTariff)
            previewCheck(type == rule.fixedType || type in rule.excludedTypes)
            if (tariff != rule.tariff || type != rule.fixedType) return@mapNotNull null
            previewCheck(row in rule.fixedRows)
            val proposalRef = proposalHeader.at(row)
            val proposal = text(proposalRef)
            previewCheck(proposal.encodeToByteArray().size in 1..96 && proposal.any { !isUnicodeSpace(it) } &&
                proposal.none { it.isISOControl() || isWorkbookBidi(it) || it == '\uFEFF' } && names.add(proposal))
            rule.absentHeaders.forEach { header ->
                val ref = header.at(row)
                if (ref in cells) previewCheck(required(ref).kind == "blank")
            }
            val prices = rule.bindings.map { binding ->
                val ref = binding.header.at(row)
                val cell = required(ref)
                previewCheck(cell.kind == "number")
                TeachDraftPreviewPrice(binding.term, binding.period, binding.unit, previewDecimal(checkNotNull(cell.value).content),
                    cite(binding.header.a1), cite(ref))
            }
            included += row
            TeachDraftPreviewRow(proposal, cite(proposalRef), tariff, cite(tariffHeader.at(row)), type, cite(typeHeader.at(row)), prices)
        }
        previewCheck(included == rule.fixedRows)
        return rows
}

private val decimalLexeme = Regex("(?:0|[1-9][0-9]{0,5})(?:\\.[0-9]{1,12})?")
private val maxDecimal = BigDecimal("999999.999999999999")

private fun previewDecimal(literal: String): String {
    // At most six integer digits and twelve fractional digits, no exponent, nonnegative only.
    previewCheck(decimalLexeme.matches(literal))
    val decimal = BigDecimal(literal)
    previewCheck(decimal.precision() <= 18 && decimal.scale() in 0..12 && decimal in BigDecimal.ZERO..maxDecimal)
    return decimal.toPlainString()
}
