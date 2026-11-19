package com.bm.backend.models

import kotlinx.serialization.Serializable

@Serializable
internal data class TeachProposalImportReceipt(
    val kind: String,
    val importId: String,
    val revisionId: String,
    val generation: String,
    val proposalCount: Int,
)
