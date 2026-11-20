package com.bm.backend.services

import com.bm.backend.models.*
import com.bm.backend.repositories.ports.*
import java.util.UUID

internal class TeachProductionPromotionService private constructor(
    private val validation: TeachProposalValidationService, private val registry: TeachActivationRegistry,
    private val store: TeachPromotionStore, private val provider: TeachPromotionProvider,
) {
    companion object {
        fun mount(validation: TeachProposalValidationService, registry: TeachActivationRegistry,
            store: TeachPromotionStore, provider: TeachPromotionProvider): TeachProductionPromotionService? {
            if (!validation.isBoundTo(registry, store.identity) || !provider.atomicFixture || provider.fixtureId != store.providerId) return null
            return try { store.verify(); TeachProductionPromotionService(validation, registry, store, provider) }
            catch (_: TeachProposalStoreUnavailable) { null }
            catch (_: TeachProposalValidationDeadline) { null }
        }
    }

    fun isBoundTo(service: TeachProposalValidationService?) = validation === service
    fun acceptsActor(uid: String) = validation.acceptsActor(uid)

    fun read(actor: String, ref: UUID, remaining: () -> Long, checkpoint: () -> Unit): TeachPromotionResult? {
        val snapshot = store.read(actor, ref, remaining, checkpoint) ?: return null
        val expected = diff(snapshot.evidence)
        if (snapshot.reservation?.let { it.digest != expected.digest } == true) unavailable()
        return result(snapshot.evidence, snapshot.reservation)
    }

    /** Blocking stages use the caller's bounded dispatcher. Reauthorization suspends outside SQL. */
    suspend fun request(actor: String, ref: UUID, remaining: () -> Long, checkpoint: () -> Unit,
        reauthorize: suspend () -> Unit): Pair<TeachPromotionResult, Int>? {
        val evidence = store.read(actor, ref, remaining, checkpoint)?.evidence ?: return null
        val diff = diff(evidence)
        reauthorize()
        val reservation = store.reserve(actor, ref, evidence, diff.digest, remaining, checkpoint) ?: return null
        if (reservation.digest != diff.digest) unavailable()
        if (reservation.proposalId != null) return result(evidence, reservation) to 200
        reauthorize()
        checkpoint()
        if (!provider.atomicFixture || provider.fixtureId != store.providerId) throw TeachPromotionProviderUnavailable()
        val proposal = TeachPromotionProposal(reservation.key, diff)
        val receipt = provider.lookup(proposal, checkpoint) ?: run {
            reauthorize()
            checkpoint()
            if (!provider.atomicFixture || provider.fixtureId != store.providerId) throw TeachPromotionProviderUnavailable()
            provider.createIfAbsent(proposal, checkpoint)
        }
        checkpoint()
        if (receipt.proposal != proposal || receipt.id <= 0 || !provider.atomicFixture || provider.fixtureId != store.providerId) throw TeachPromotionProviderUnavailable()
        val (opened, inserted) = store.finalize(actor, ref, reservation, receipt.id, remaining, checkpoint)
        if (opened.proposalId != receipt.id || opened.key != reservation.key || opened.digest != reservation.digest) unavailable()
        return result(evidence, opened) to if (inserted) 201 else 200
    }

    private fun diff(evidence: TeachPromotionEvidence): TeachPromotionDiff {
        if (evidence.manifest != registry.manifest) unavailable()
        return projectTeachPromotion(registry.exact(evidence.supplier, evidence.revision) ?: unavailable(), registry.manifest)
    }

    private fun result(evidence: TeachPromotionEvidence, row: TeachPromotionReservation?): TeachPromotionResult {
        val id = row?.proposalId
        if ((id == null) != (row?.openedAt == null) || (id != null && id <= 0)) unavailable()
        return TeachPromotionResult(if (id != null) "opened" else if (row != null) "pending" else "notRequested",
            evidence.importId, evidence.revision, evidence.generation, evidence.validatedAt,
            id?.let { "https://pr-pilot.invalid/bm-pilot/skills-fixture/pull/$it" }, row?.openedAt)
    }

    private fun unavailable(): Nothing = throw TeachProposalStoreUnavailable()
}
