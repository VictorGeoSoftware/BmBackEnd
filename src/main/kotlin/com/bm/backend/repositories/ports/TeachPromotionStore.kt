package com.bm.backend.repositories.ports

import com.bm.backend.models.TeachPromotionEvidence
import com.bm.backend.models.TeachPromotionReservation
import com.bm.backend.models.TeachPromotionSnapshot
import com.bm.backend.models.TeachProposalImportIdentity
import java.util.UUID

internal interface TeachPromotionStore {
    val identity: TeachProposalImportIdentity
    val providerId: UUID
    fun verify()
    fun read(actor: String, ref: UUID, remaining: () -> Long, checkpoint: () -> Unit): TeachPromotionSnapshot?
    fun reserve(actor: String, ref: UUID, evidence: TeachPromotionEvidence, digest: String,
        remaining: () -> Long, checkpoint: () -> Unit): TeachPromotionReservation?
    fun finalize(actor: String, ref: UUID, reservation: TeachPromotionReservation, id: Long,
        remaining: () -> Long, checkpoint: () -> Unit): Pair<TeachPromotionReservation, Boolean>
}
