package com.bm.backend.models

import kotlinx.serialization.Serializable

@Serializable
internal data class TeachProposalValidationEvidence(val kind: String, val validatedAt: String)
