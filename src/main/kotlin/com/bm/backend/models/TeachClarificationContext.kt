package com.bm.backend.models

internal data class TeachClarificationContext(
    val candidates: List<TeachClarificationCandidate>,
    val answer: String? = null,
    val references: List<TeachClarificationReference> = emptyList(),
)
