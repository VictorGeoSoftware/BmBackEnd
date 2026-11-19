package com.bm.backend.models

internal class TeachClarificationFrame(
    val workbook: ByteArray,
    val workbookSha256: String,
    val expectedSha256: String?,
    val answer: String?,
    val pdf: ByteArray?,
    val png: ByteArray?,
)
