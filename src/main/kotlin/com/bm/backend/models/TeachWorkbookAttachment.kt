package com.bm.backend.models

import kotlinx.serialization.Serializable

@Serializable
data class TeachWorkbookAttachment(val kind: String, val filename: String, val sizeBytes: Int)
