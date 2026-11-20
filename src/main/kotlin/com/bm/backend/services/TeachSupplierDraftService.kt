package com.bm.backend.services

import com.bm.backend.models.DraftSupplierSkillV1
import com.bm.backend.models.TeachClarificationCitation
import com.bm.backend.models.TeachClarificationResult
import com.bm.backend.models.TeachSupplierColumnChoice
import com.bm.backend.models.TeachSupplierDraftFrame
import com.bm.backend.models.TeachSupplierDraftRule
import com.bm.backend.models.TeachVerifiedHeader
import kotlinx.coroutines.CancellationException
import java.security.MessageDigest
import java.util.HexFormat

internal class TeachSupplierDraftConflict : RuntimeException()
internal class InvalidTeachSupplierDraft : RuntimeException()

private const val SUPPLIER = "demo-electricidad"
private const val RULE = "fixed_price_column"
// Bytes of src/test/resources/ensename/ensename-demo.xlsx; never read it in a request.
private const val PROFILE_HASH = "6c276ce72fb3e3f084920f46bf04bb706af87f339f4898d0b796252c3107c31e"
private val profileCitations = TeachSupplierColumnChoice.entries.map { TeachClarificationCitation("Tarifas", it.a1) }.toSet()

internal class TeachSupplierDraftService(
    private val validator: TeachWorkbookValidator,
    private val inspector: TeachWorkbookInspector,
    private val adapter: TeachSupplierDraftAdapter,
) {
    fun question(frame: TeachSupplierDraftFrame, checkpoint: () -> Unit): TeachClarificationResult {
        val headers = verify(frame, checkpoint) ?: return TeachClarificationResult.NotApplicable
        return TeachClarificationResult.Question(frame.workbookSha256, headers.map { it.citation })
    }

    fun draft(frame: TeachSupplierDraftFrame, checkpoint: () -> Unit): DraftSupplierSkillV1 {
        if (frame.supplierKey != SUPPLIER) throw InvalidTeachSupplierDraft()
        if (frame.expectedSha256 != frame.workbookSha256) throw TeachSupplierDraftConflict()
        val headers = verify(frame, checkpoint) ?: throw TeachSupplierDraftConflict()
        val choice = checkNotNull(frame.choice)
        val index = headers.indexOfFirst { it.citation.a1 == choice.a1 }
        val selected = revision(frame.workbookSha256, headers[index].citation)
        frame.priorRevisionId?.let { prior ->
            if (prior == selected.revisionId) throw InvalidTeachSupplierDraft()
            if (prior != revision(frame.workbookSha256, headers[1 - index].citation).revisionId) throw TeachSupplierDraftConflict()
        }
        checkpoint()
        val suggestion = try {
            adapter.select(headers.map { it.descriptor }, choice)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            // Port failures are never attributed to the request or reflected.
            error("Supplier draft adapter failed")
        }
        checkpoint()
        // Never allow the port to change the explicit choice or the verified mapping.
        check(suggestion in headers.indices && suggestion == index)
        return selected
    }

    private fun verify(frame: TeachSupplierDraftFrame, checkpoint: () -> Unit): List<TeachVerifiedHeader>? {
        checkpoint()
        if (frame.workbookSha256 != PROFILE_HASH) throw TeachSupplierDraftConflict()
        val headers = verifiedTeachFixedPriceHeaders(validator, inspector, frame.workbook, checkpoint) ?: return null
        return headers.takeIf { it.map { header -> header.citation }.toSet() == profileCitations }
    }

    private fun revision(digest: String, header: TeachClarificationCitation): DraftSupplierSkillV1 {
        val canonical = listOf("bm.teach.supplier-draft/v1", SUPPLIER, digest, RULE, header.sheet, header.a1).joinToString("\n")
        val revisionId = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.encodeToByteArray()))
        return DraftSupplierSkillV1(1, SUPPLIER, digest, TeachSupplierDraftRule(RULE, header), revisionId)
    }
}
