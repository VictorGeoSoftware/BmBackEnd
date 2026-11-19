package com.bm.backend.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive

@Serializable
internal data class TeachWorkbookCell(val a1: String, val kind: String, val value: JsonPrimitive? = null)
