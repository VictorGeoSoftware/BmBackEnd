package com.bm.backend.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal sealed interface TeachClarificationResult {
    @Serializable @SerialName("question")
    data class Question(val workbookSha256: String, val candidates: List<TeachClarificationCitation>) : TeachClarificationResult

    @Serializable @SerialName("not_applicable")
    data object NotApplicable : TeachClarificationResult

    @Serializable @SerialName("acknowledged")
    data object Acknowledged : TeachClarificationResult
}
