package com.bm.backend.models

import kotlinx.serialization.Serializable

@Serializable
internal data class TeachProposalImportBatch(
    val importId: String,
    val supplierKey: String,
    val revisionId: String,
    val generation: String,
    val importedAt: String,
    val proposals: List<TeachDraftPreviewRow>,
)
