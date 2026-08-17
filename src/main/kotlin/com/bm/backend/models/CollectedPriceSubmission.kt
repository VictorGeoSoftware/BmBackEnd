package com.bm.backend.models

/**
 * Domain input model for a collected-prices submission, before normalization and
 * policy checks are applied by `CollectedPricesService`.
 *
 * Kept separate from [CollectedPrice] (the persisted model) so the service is the
 * only place that decides the canonical supplier/tariff form, and so no transport
 * type reaches the service layer.
 *
 * Period maps are keyed "P1".."P6"; absent periods simply have no entry.
 */
data class CollectedPriceSubmission(
    val companyName: String,
    val tariffType: String,
    val powerPricesByPeriod: Map<String, Double>,
    val energyPricesByPeriod: Map<String, Double>,
    val extraServices: Double?
)
