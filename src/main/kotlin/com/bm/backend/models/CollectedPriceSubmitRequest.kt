package com.bm.backend.models

import kotlinx.serialization.Serializable

/**
 * Transport DTO for `POST /api/v1/collected-prices`, submitted by the app when a
 * broker moves from the current-conditions screen to the price proposals.
 *
 * Period maps are keyed "P1".."P6". The app only sends the periods that apply to the
 * customer's tariff, so both maps are expected to be partial.
 *
 * All fields are nullable with defaults so that a malformed body produces a clear
 * 400 from [toDomainModel] rather than a deserialization failure.
 */
@Serializable
data class CollectedPriceSubmitRequest(
    val companyName: String? = null,
    val tariffType: String? = null,
    val powerPrices: Map<String, Double>? = null,
    val energyPrices: Map<String, Double>? = null,
    val extraServices: Double? = null
)

/**
 * Converts the DTO to its domain input model, validating structure only.
 *
 * Normalization (canonical supplier/tariff form) and policy (2.0TD exclusion) are
 * deliberately NOT done here — they are business rules and belong to
 * `CollectedPricesService`.
 *
 * @throws IllegalArgumentException when a required field is missing or a period key
 * is not one of P1..P6, which routes translate to 400 Bad Request.
 */
fun CollectedPriceSubmitRequest.toDomainModel(): CollectedPriceSubmission {
    require(!companyName.isNullOrBlank()) { "companyName is required" }
    require(!tariffType.isNullOrBlank()) { "tariffType is required" }

    val power = powerPrices.orEmpty()
    val energy = energyPrices.orEmpty()
    require(power.isNotEmpty() || energy.isNotEmpty()) {
        "at least one of powerPrices or energyPrices is required"
    }
    validatePeriodKeys(power, "powerPrices")
    validatePeriodKeys(energy, "energyPrices")

    return CollectedPriceSubmission(
        companyName = companyName,
        tariffType = tariffType,
        powerPricesByPeriod = power,
        energyPricesByPeriod = energy,
        extraServices = extraServices
    )
}

private val PERIOD_KEY_REGEX = Regex("^P[1-6]$")

private fun validatePeriodKeys(prices: Map<String, Double>, fieldName: String) {
    val invalidKeys = prices.keys.filterNot { key -> PERIOD_KEY_REGEX.matches(key) }
    require(invalidKeys.isEmpty()) {
        "$fieldName contains invalid period keys ${invalidKeys.sorted()}; expected P1..P6"
    }
}
