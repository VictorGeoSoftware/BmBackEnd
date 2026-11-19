package com.bm.backend.repositories.ports

import com.bm.backend.models.TeachProposalImportIdentity
import com.bm.backend.models.TeachProposalValidationResult
import java.util.UUID

internal class TeachProposalValidationDeadline : RuntimeException()

/** Append/replay only, on the same isolated store as the committed imports. */
internal interface TeachProposalValidationStore {
    val identity: TeachProposalImportIdentity
    fun verify()
    fun confirm(actor: String, ref: UUID, remaining: () -> Long, checkpoint: () -> Unit): TeachProposalValidationResult?
}
