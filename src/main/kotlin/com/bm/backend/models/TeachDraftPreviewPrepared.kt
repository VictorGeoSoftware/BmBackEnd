package com.bm.backend.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@SerialName("draft")
internal data class TeachDraftPreviewPrepared(val draft: DraftSupplierSkillV2) : TeachDraftPreviewResult
