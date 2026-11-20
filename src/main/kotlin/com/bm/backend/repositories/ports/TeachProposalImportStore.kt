package com.bm.backend.repositories.ports

import com.bm.backend.models.TeachProposalImportAttempt
import com.bm.backend.models.TeachProposalImportIdentity
import com.bm.backend.models.TeachProposalImportPage
import com.bm.backend.models.TeachProposalImportPending
import com.bm.backend.models.TeachProposalImportReceipt
import com.bm.backend.models.TeachValidatedImportPage

internal class TeachProposalStoreUnavailable : RuntimeException()
internal class TeachProposalAttemptMismatch : RuntimeException()

/** No operational implementation. Only a fixture can supply bound read/write capabilities. */
internal interface TeachProposalImportStore {
    val identity: TeachProposalImportIdentity
    fun verify()
    /** Read-only probe; a mismatch is disclosed only by replay after authorization is rechecked. */
    fun exists(attempt: TeachProposalImportAttempt, remaining: () -> Long, checkpoint: () -> Unit): Boolean
    /** Atomically claim and write, or return the already committed receipt. Null pending allows replay only. */
    fun record(attempt: TeachProposalImportAttempt, pending: TeachProposalImportPending?, remaining: () -> Long,
               checkpoint: () -> Unit): TeachProposalImportReceipt
    fun list(actor: String, before: Long?, remaining: () -> Long, checkpoint: () -> Unit): TeachProposalImportPage
    fun verifyValidationProjection(): Unit = throw TeachProposalStoreUnavailable()
    fun listWithValidation(actor: String, before: Long?, remaining: () -> Long, checkpoint: () -> Unit): TeachValidatedImportPage =
        throw TeachProposalStoreUnavailable()
}
