package com.bm.backend.models

import kotlinx.serialization.Serializable

@Serializable
internal data class TeachActivationRequest(
    val revisionId: String, val expectedRevisionId: String?, val expectedGeneration: String, val receipt: String,
)
