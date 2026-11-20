package com.bm.backend.models

import kotlinx.serialization.Serializable

@Serializable
internal data class TeachProposalImportPage(val kind: String, val batches: List<TeachProposalImportBatch>, val nextCursor: String?)
