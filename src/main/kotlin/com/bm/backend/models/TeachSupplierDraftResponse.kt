package com.bm.backend.models

import kotlinx.serialization.Serializable

@Serializable
internal data class TeachSupplierDraftResponse(val kind: String, val draft: DraftSupplierSkillV1)
