package com.bm.backend.testing

import com.bm.backend.models.TeachPromotionDiff
import com.bm.backend.models.TeachPromotionProposal
import com.bm.backend.models.TeachPromotionReceipt
import com.bm.backend.repositories.ports.TeachPromotionProvider
import com.bm.backend.repositories.ports.TeachPromotionProviderUnavailable
import com.bm.backend.services.teachRevisionId
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** No transport, filesystem or Git. State belongs to this instance, not to Netty. */
internal class TeachPromotionFake(
    allowed: List<TeachPromotionDiff>,
    private val beforeLookup: (() -> Unit) -> Unit = {},
    private val afterCreate: (TeachPromotionReceipt, () -> Unit) -> TeachPromotionReceipt = { receipt, _ -> receipt },
) : TeachPromotionProvider, AutoCloseable {
    override val fixtureId: UUID = UUID.randomUUID()
    override val atomicFixture = true
    private val exact = allowed.associateBy { it.revision }
    private val opened = LinkedHashMap<UUID, TeachPromotionReceipt>()
    val lookups = AtomicInteger()
    val creates = AtomicInteger()
    val protectedBase = emptyMap<String, String>() // no mutation API

    private fun verify(proposal: TeachPromotionProposal) {
        val revision = proposal.diff.revision
        if (!teachRevisionId.matches(revision) || exact[revision] != proposal.diff ||
            proposal.diff.path != "skills-fixture/gesternova/$revision.json" || proposal.diff.path in protectedBase ||
            proposal.destination != "bm-pilot/skills-fixture" || proposal.base != "protected-fixture" || proposal.head != "teach-pilot/${proposal.key}") fail()
    }

    override fun lookup(proposal: TeachPromotionProposal, checkpoint: () -> Unit): TeachPromotionReceipt? {
        checkpoint()
        verify(proposal)
        lookups.incrementAndGet()
        beforeLookup(checkpoint)
        return synchronized(opened) { checkpoint(); find(proposal) }
    }

    override fun createIfAbsent(proposal: TeachPromotionProposal, checkpoint: () -> Unit): TeachPromotionReceipt {
        checkpoint()
        verify(proposal)
        creates.incrementAndGet()
        val receipt = synchronized(opened) {
            checkpoint()
            find(proposal) ?: TeachPromotionReceipt(proposal, opened.size.toLong() + 1).also { opened[proposal.key] = it }
        }
        return afterCreate(receipt, checkpoint).also { checkpoint() }
    }

    fun inspect(): List<TeachPromotionReceipt> = synchronized(opened) { opened.values.toList() }

    override fun close() { synchronized(opened) { opened.clear() } }

    private fun find(proposal: TeachPromotionProposal) = opened[proposal.key]?.also { if (it.proposal != proposal) fail() }
    private fun fail(): Nothing = throw TeachPromotionProviderUnavailable()
}
