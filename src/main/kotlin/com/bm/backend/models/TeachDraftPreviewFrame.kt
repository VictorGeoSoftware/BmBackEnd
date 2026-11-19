package com.bm.backend.models

internal data class TeachDraftPreviewFrame(
    val workbook: ByteArray,
    val workbookSha256: String,
    val profileKey: String,
    val selection: TeachDraftPreviewSelection?,
)
