package com.bm.backend.models

import java.util.UUID

internal data class TeachPromotionReservation(
    val key: UUID, val digest: String, val proposalId: Long?, val openedAt: String?,
)
