package com.bm.backend.repositories.ports

import com.bm.backend.models.TeachActiveSkill
import com.bm.backend.models.TeachActivationClaim
import com.bm.backend.models.TeachActivationResult

/** No runtime implementation. Each operation verifies the fixture identity and manifest in its transaction. */
internal interface TeachActivationStore {
    val storeId: String
    fun verify(manifest: String): Map<String, TeachActiveSkill>
    fun read(manifest: String, supplier: String, remainingMillis: () -> Long, checkpoint: () -> Unit): TeachActiveSkill
    fun activate(manifest: String, claim: TeachActivationClaim, remainingMillis: () -> Long,
                 mutationTime: () -> Long): TeachActivationResult
}
