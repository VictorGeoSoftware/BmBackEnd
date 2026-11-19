package com.bm.backend.models

import java.util.UUID

internal data class TeachPromotionProposal(val key: UUID, val diff: TeachPromotionDiff) {
    val destination get() = "bm-pilot/skills-fixture"
    val base get() = "protected-fixture"
    val head get() = "teach-pilot/$key"
}
