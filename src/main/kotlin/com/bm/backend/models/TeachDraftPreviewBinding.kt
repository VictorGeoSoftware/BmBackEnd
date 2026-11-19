package com.bm.backend.models

import kotlinx.serialization.Serializable

@Serializable
internal data class TeachDraftPreviewBinding(val term: String, val period: String, val unit: String, val header: TeachDraftPreviewHeader)
