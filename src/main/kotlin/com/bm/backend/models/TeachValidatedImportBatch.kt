package com.bm.backend.models

import kotlinx.serialization.Serializable

@Serializable
internal data class TeachValidatedImportBatch(
    val importId: String,
    val supplierKey: String,
    val revisionId: String,
    val generation: String,
    val importedAt: String,
    val proposals: List<TeachDraftPreviewRow>,
    val validationRef: String,
    val validation: TeachProposalValidationEvidence?,
) {
    constructor(batch: TeachProposalImportBatch, ref: String, evidence: TeachProposalValidationEvidence?) :
        this(batch.importId, batch.supplierKey, batch.revisionId, batch.generation, batch.importedAt,
            batch.proposals, ref, evidence)
}
