package com.bm.backend.repositories.ports

import com.bm.backend.models.TeachPromotionProposal
import com.bm.backend.models.TeachPromotionReceipt
import java.util.UUID

/** Fixture-only contract. A new/empty provider instance MUST have a different identity. */
internal interface TeachPromotionProvider {
    val fixtureId: UUID
    val atomicFixture: Boolean
    fun lookup(proposal: TeachPromotionProposal, checkpoint: () -> Unit): TeachPromotionReceipt?
    fun createIfAbsent(proposal: TeachPromotionProposal, checkpoint: () -> Unit): TeachPromotionReceipt
}

internal class TeachPromotionProviderUnavailable : RuntimeException()
