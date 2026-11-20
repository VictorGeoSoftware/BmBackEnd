package com.bm.backend.models

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

@OptIn(ExperimentalSerializationApi::class)
@Serializable
internal data class TeachQaPilotPreview(
    val preview: TeachDraftPreviewResult, val active: TeachActiveSkill, val expiresAt: Long, val receipt: String,
    @EncodeDefault val kind: String = "qaPilotPreview",
)
