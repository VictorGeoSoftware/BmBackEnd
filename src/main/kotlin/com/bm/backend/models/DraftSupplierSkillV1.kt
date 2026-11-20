package com.bm.backend.models

import kotlinx.serialization.Serializable

@Serializable
internal data class DraftSupplierSkillV1(
    val schemaVersion: Int,
    val supplierKey: String,
    val workbookSha256: String,
    val rule: TeachSupplierDraftRule,
    val revisionId: String,
)
