package com.bm.backend.models

/** Proposal store identity is bound to the exact isolated QA catalogue. */
internal data class TeachProposalImportIdentity(
    val storeId: String,
    val catalogId: String,
    val manifest: String,
    val environment: String = "qa",
    val schemaVersion: Int = 1,
)
