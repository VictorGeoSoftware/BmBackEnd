package com.bm.backend.models

import kotlinx.serialization.Serializable

@Serializable
internal data class TeachWorkbookInspection(val version: Int, val dateSystem: String, val sheets: List<TeachWorkbookSheet>)
