package com.bm.backend.services

import com.bm.backend.models.TeachClarificationCandidate
import com.bm.backend.models.TeachClarificationCitation
import com.bm.backend.models.TeachClarificationContext
import com.bm.backend.models.TeachClarificationFrame
import com.bm.backend.models.TeachClarificationReference
import com.bm.backend.models.TeachClarificationReference.Kind
import com.bm.backend.models.TeachClarificationResult
import com.bm.backend.services.TeachClarificationAdapter.Decision

internal class TeachClarificationConflict : RuntimeException()

internal class TeachClarificationService(
    private val validator: TeachWorkbookValidator,
    private val inspector: TeachWorkbookInspector,
    private val referenceValidator: TeachReferenceValidator,
    private val adapter: TeachClarificationAdapter,
) {
    fun clarify(frame: TeachClarificationFrame, checkpoint: () -> Unit): TeachClarificationResult {
        checkpoint()
        val reply = frame.answer != null
        if (reply && frame.expectedSha256 != frame.workbookSha256) throw TeachClarificationConflict()
        val inspection = inspector.inspectForClarification(validator, frame.workbook, frame.workbook.size, checkpoint)
        if (inspection.headersBySheet.any { it == null }) return notApplicable(reply)
        val sheets = inspection.workbook.sheets
        val candidates = ArrayList<TeachClarificationCandidate>(2)
        for ((ordinal, sheet) in sheets.withIndex()) {
            if (sheet.state != "visible") continue
            val headers = checkNotNull(inspection.headersBySheet[ordinal]).toHashSet()
            for (cell in sheet.cells) {
                checkpoint()
                if (cell.kind != "text" || cell.a1 !in headers || cell.value?.content != "Precio fijo") continue
                if (candidates.size == 2) return notApplicable(reply)
                candidates.add(TeachClarificationCandidate(ordinal, cell.a1))
            }
        }
        if (candidates.size != 2) return notApplicable(reply)
        val references = buildList {
            frame.pdf?.let { referenceValidator.validate(it, it.size, true, checkpoint); add(TeachClarificationReference(Kind.PDF, it)) }
            frame.png?.let { referenceValidator.validate(it, it.size, false, checkpoint); add(TeachClarificationReference(Kind.PNG, it)) }
        }
        checkpoint()
        // Keep the verified mapping separate from the adapter's input.
        val decision = adapter.respond(TeachClarificationContext(candidates.toList(), frame.answer, references))
        checkpoint()
        return if (reply) {
            check(decision == Decision.ACKNOWLEDGE_REPLY)
            TeachClarificationResult.Acknowledged
        } else {
            check(decision == Decision.ASK_CANDIDATES)
            TeachClarificationResult.Question(frame.workbookSha256, candidates.map {
                TeachClarificationCitation(sheets[it.sheetOrdinal].name, it.a1)
            })
        }
    }

    private fun notApplicable(reply: Boolean): TeachClarificationResult {
        if (reply) throw TeachClarificationConflict()
        return TeachClarificationResult.NotApplicable
    }
}
