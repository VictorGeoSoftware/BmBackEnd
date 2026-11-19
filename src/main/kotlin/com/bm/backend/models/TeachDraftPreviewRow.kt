package com.bm.backend.models

import kotlinx.serialization.Serializable

@Serializable
internal data class TeachDraftPreviewRow(
    val proposal: String,
    val proposalCell: TeachClarificationCitation,
    val tariff: String,
    val tariffCell: TeachClarificationCitation,
    val priceType: String,
    val typeCell: TeachClarificationCitation,
    val prices: List<TeachDraftPreviewPrice>,
)
