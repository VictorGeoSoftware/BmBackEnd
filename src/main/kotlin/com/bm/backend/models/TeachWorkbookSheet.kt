package com.bm.backend.models

import kotlinx.serialization.Serializable

@Serializable
internal data class TeachWorkbookSheet(val name: String, val state: String, val headers: List<String>, val cells: List<TeachWorkbookCell>)
