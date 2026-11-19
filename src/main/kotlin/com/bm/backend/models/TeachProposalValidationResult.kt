package com.bm.backend.models

import kotlinx.serialization.Serializable

@Serializable
internal data class TeachProposalValidationResult(
    val kind: String,
    val importId: String,
    val revisionId: String,
    val generation: String,
    val validatedAt: String,
)
