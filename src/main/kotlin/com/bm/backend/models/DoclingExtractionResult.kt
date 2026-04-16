package com.bm.backend.models

data class DoclingExtractionResult(
    val extractedData: DoclingExtractedData,
    val currentConditions: CustomerCurrentConditions? = null,
)
