package com.bm.backend.models

import java.time.Instant

/**
 * Domain model for one row of `collected_prices`: the electricity prices a customer
 * was paying at the time a broker prepared a comparison for them.
 *
 * Holds no customer personal data and no broker identity by design — only the
 * supplier, the tariff, the prices and when they were collected.
 *
 * Period lists are indexed P1..P6 in order and may contain nulls for periods that do
 * not apply to the tariff. Both lists are always exactly [PERIOD_COUNT] long so that
 * callers can index by period without bounds checks.
 */
data class CollectedPrice(
    val id: Int? = null,
    val companyName: String,
    val companyNameNormalized: String,
    val tariffType: String,
    val powerPrices: List<Double?>,
    val energyPrices: List<Double?>,
    val extraServices: Double?,
    val collectedAt: Instant
) {
    init {
        require(powerPrices.size == PERIOD_COUNT) {
            "powerPrices must have $PERIOD_COUNT entries (P1..P6), was ${powerPrices.size}"
        }
        require(energyPrices.size == PERIOD_COUNT) {
            "energyPrices must have $PERIOD_COUNT entries (P1..P6), was ${energyPrices.size}"
        }
    }

    companion object {
        /** P1..P6. The maximum number of billing periods any Spanish tariff uses. */
        const val PERIOD_COUNT = 6
    }
}
