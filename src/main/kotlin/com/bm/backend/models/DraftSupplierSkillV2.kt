package com.bm.backend.models

import kotlinx.serialization.Serializable

@Serializable
internal data class DraftSupplierSkillV2(
    val schemaVersion: Int,
    val profileKey: String,
    val supplierKey: String,
    val workbookSha256: String,
    val rule: TeachDraftPreviewRule,
    val revisionId: String,
)
