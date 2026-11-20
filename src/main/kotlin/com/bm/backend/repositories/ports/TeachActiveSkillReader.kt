package com.bm.backend.repositories.ports

import com.bm.backend.models.TeachActiveSkill

/** Dedicated QA read capability. Each call verifies store identity and reads within one snapshot. */
internal interface TeachActiveSkillReader {
    val storeId: String
    fun verify(manifest: String): Map<String, TeachActiveSkill>
    fun read(manifest: String, supplier: String, remainingMillis: () -> Long, checkpoint: () -> Unit): TeachActiveSkill?
}

internal class TeachSkillCatalogUnavailable : RuntimeException()
