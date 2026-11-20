package com.bm.backend.models

import kotlinx.serialization.Serializable

@Serializable
internal data class TeachDraftPreviewRule(
    val kind: String,
    val sheet: String,
    val candidateRows: List<Int>,
    val fixedRows: List<Int>,
    val tariff: String,
    val otherTariff: String,
    val fixedType: String,
    val excludedTypes: List<String>,
    val headers: List<TeachDraftPreviewHeader>,
    val bindings: List<TeachDraftPreviewBinding>,
    val absentHeaders: List<TeachDraftPreviewHeader>,
    val styleSha256: String,
    val decimalPolicy: String,
)
