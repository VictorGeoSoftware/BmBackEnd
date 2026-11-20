package com.bm.backend.models

import kotlinx.serialization.Serializable

@Serializable
internal data class TeachSupplierDraftRule(val kind: String, val header: TeachClarificationCitation)
