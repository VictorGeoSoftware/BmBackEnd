package com.bm.backend.models

internal data class TeachSupplierDraftFrame(
    val workbook: ByteArray,
    val workbookSha256: String,
    val expectedSha256: String?,
    val supplierKey: String?,
    val priorRevisionId: String?,
    val choice: TeachSupplierColumnChoice?,
)
