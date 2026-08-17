package com.bm.backend.models

import kotlinx.serialization.Serializable

/**
 * Transport DTO for `GET /api/v1/admin/collected-prices`.
 *
 * Unlike the other admin list endpoints, this one is paginated: collected prices grow
 * by one row per customer visit and would otherwise be unbounded.
 *
 * [total] is the number of rows matching the active filters, ignoring pagination, so
 * the dashboard can render page controls.
 */
@Serializable
data class CollectedPriceListResponse(
    val success: Boolean,
    val items: List<CollectedPriceResponse>,
    val total: Long,
    val limit: Int,
    val offset: Int
)
