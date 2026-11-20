package com.bm.backend.models

/** Request-only companion to the public inspection. A null entry means unknown visibility. */
internal data class TeachClarificationInspection(
    val workbook: TeachWorkbookInspection,
    val headersBySheet: List<List<String>?>,
)
