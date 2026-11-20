package com.bm.backend.services

import com.bm.backend.models.TeachClarificationCandidate
import com.bm.backend.models.TeachClarificationCitation
import com.bm.backend.models.TeachVerifiedHeader

/** Exactly two visible literal headers, or no usable ambiguity. */
internal fun verifiedTeachFixedPriceHeaders(
    validator: TeachWorkbookValidator,
    inspector: TeachWorkbookInspector,
    workbook: ByteArray,
    checkpoint: () -> Unit,
): List<TeachVerifiedHeader>? {
    val inspection = inspector.inspectForClarification(validator, workbook, workbook.size, checkpoint)
    if (inspection.headersBySheet.any { it == null }) return null
    val candidates = ArrayList<TeachVerifiedHeader>(2)
    for ((ordinal, sheet) in inspection.workbook.sheets.withIndex()) {
        if (sheet.state != "visible") continue
        val headers = checkNotNull(inspection.headersBySheet[ordinal]).toHashSet()
        for (cell in sheet.cells) {
            checkpoint()
            if (cell.kind != "text" || cell.a1 !in headers || cell.value?.content != "Precio fijo") continue
            if (candidates.size == 2) return null
            candidates.add(TeachVerifiedHeader(TeachClarificationCandidate(ordinal, cell.a1), TeachClarificationCitation(sheet.name, cell.a1)))
        }
    }
    return candidates.takeIf { it.size == 2 }
}
