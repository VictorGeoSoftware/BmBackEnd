package com.bm.backend.models

import kotlinx.serialization.Serializable

/**
 * Transport DTO for one collected-prices row returned to the BmWeb dashboard.
 *
 * Period lists are always 6 entries long, ordered P1..P6, with nulls for periods
 * that do not apply to the tariff.
 *
 * [collectedAt] is epoch millis, matching the convention used by the other admin
 * list endpoints.
 */
@Serializable
data class CollectedPriceResponse(
    val id: Int,
    val companyName: String,
    val tariffType: String,
    val powerPrices: List<Double?>,
    val energyPrices: List<Double?>,
    val extraServices: Double?,
    val collectedAt: Long
)
