package com.bm.backend.services

import com.bm.backend.models.TeachActivationClaim
import com.bm.backend.models.TeachActivationRequest
import com.bm.backend.models.TeachActivationResult
import com.bm.backend.models.TeachActiveSkill
import com.bm.backend.models.TeachDraftPreviewFrame
import com.bm.backend.models.TeachDraftPreviewOutput
import com.bm.backend.models.TeachQaPilotPreview
import com.bm.backend.repositories.ports.TeachActivationStore
import java.security.SecureRandom
import java.time.Clock
import java.util.HexFormat

internal class TeachSkillActivationService private constructor(
    private val store: TeachActivationStore,
    private val registry: TeachActivationRegistry,
    private val clock: Clock,
    key: ByteArray,
) {
    private val random = SecureRandom()
    private val receipts = TeachActivationReceipts(key, store.storeId, registry.manifest)

    companion object {
        /** The composition root must supply a verified dedicated store; no environment-based factory. */
        fun mount(store: TeachActivationStore, registry: TeachActivationRegistry, clock: Clock, key: ByteArray): TeachSkillActivationService? {
            if (key.size != 32 || !teachFixtureId.matches(store.storeId)) return null
            val pointers = runCatching { store.verify(registry.manifest) }.getOrNull() ?: return null
            if (pointers.any { (supplier, active) -> !validTeachActive(active.revisionId, active.generation) ||
                    active.revisionId?.let { registry.resolves(supplier, it) } != true }) return null
            return TeachSkillActivationService(store, registry, clock, key)
        }
    }

    fun preview(preview: TeachDraftPreviewService, frame: TeachDraftPreviewFrame, actor: String,
                remainingMillis: () -> Long, checkpoint: () -> Unit): TeachQaPilotPreview {
        activationCheck(teachSyntheticActor.matches(actor))
        val output = preview.executePinned(frame, registry.containsPin(frame.workbookSha256), checkpoint) as TeachDraftPreviewOutput
        checkpoint()
        val active = store.read(registry.manifest, output.supplierKey, remainingMillis, checkpoint)
        if (!validTeachActive(active.revisionId, active.generation) ||
            active.revisionId?.let { !registry.resolves(output.supplierKey, it) } == true) throw TeachActivationUnavailable()
        checkpoint()
        val now = clock.instant().epochSecond
        check(now in 0..(MAX_SAFE_JSON_INTEGER - TEACH_ACTIVATION_TTL_SECONDS))
        val claim = TeachActivationClaim(actor, output.supplierKey, output.profileKey, output.workbookSha256,
            output.revisionId, active, HexFormat.of().formatHex(ByteArray(16).also(random::nextBytes)), now, now + TEACH_ACTIVATION_TTL_SECONDS)
        return TeachQaPilotPreview(output, active, claim.expiresAt, receipts.issue(claim))
    }

    fun verify(request: TeachActivationRequest, actor: String): TeachActivationClaim {
        val claim = receipts.verify(request.receipt)
        activationCheck(claim.actor == actor && claim.revisionId == request.revisionId &&
            claim.expected == TeachActiveSkill(request.expectedRevisionId, request.expectedGeneration) &&
            claim.profileKey == GESTERNOVA_PREVIEW_PROFILE && registry.resolves(claim.supplierKey, claim.revisionId, claim.workbookSha256) &&
            claim.expected.revisionId?.let { !registry.resolves(claim.supplierKey, it) } != true)
        current(claim)
        return claim
    }

    private fun current(claim: TeachActivationClaim): Long = clock.instant().epochSecond.also {
        activationCheck(it >= claim.issuedAt && it < claim.expiresAt)
    }

    fun activate(claim: TeachActivationClaim, remainingMillis: () -> Long, checkpoint: () -> Unit): TeachActivationResult =
        store.activate(registry.manifest, claim, remainingMillis) { checkpoint(); current(claim) }
}
