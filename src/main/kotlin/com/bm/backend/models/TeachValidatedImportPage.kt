package com.bm.backend.models

import kotlinx.serialization.Serializable

@Serializable
internal data class TeachValidatedImportPage(
    val kind: String,
    val batches: List<TeachValidatedImportBatch>,
    val nextCursor: String?,
)
