package com.bm.backend.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@SerialName("preview")
internal data class TeachDraftPreviewOutput(
    val profileKey: String,
    val supplierKey: String,
    val workbookSha256: String,
    val revisionId: String,
    val rows: List<TeachDraftPreviewRow>,
) : TeachDraftPreviewResult
