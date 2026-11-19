package com.bm.backend.models

import kotlinx.serialization.Serializable

@Serializable
internal data class TeachActiveSkill(val revisionId: String?, val generation: String)
