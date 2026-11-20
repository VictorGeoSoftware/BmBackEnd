package com.bm.backend.models

internal data class TeachDraftPreviewInspection(
    val sheet: TeachWorkbookSheet,
    val rowVisibility: Map<Int, Boolean>,
    val visibleCells: Set<String>,
    val defaultStyleCells: Set<String>,
    val stylesSha256: String,
)
