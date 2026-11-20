package com.bm.backend.models

import kotlinx.serialization.Serializable

@Serializable
internal data class TeachDraftPreviewPrice(
    val term: String,
    val period: String,
    val unit: String,
    val decimal: String,
    val header: TeachClarificationCitation,
    val cell: TeachClarificationCitation,
)
