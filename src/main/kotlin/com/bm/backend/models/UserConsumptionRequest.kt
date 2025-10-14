package com.bm.backend.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * DTO for receiving user consumption data from external requests.
 * All fields are nullable to handle various incoming data structures.
 */
@Serializable
data class UserConsumptionRequest(
    val feeType: String? = null,
    @SerialName("data")
    val data: List<ConsumptionPeriodRequest>? = null,
    val cups: String? = null,
    val annualConsumption: Double? = null,
    val annualConsumptionP1: Double? = null,
    val annualConsumptionP2: Double? = null,
    val annualConsumptionP3: Double? = null,
    val annualConsumptionP4: Double? = null,
    val annualConsumptionP5: Double? = null,
    val annualConsumptionP6: Double? = null,
    val subscribedPowerP1: Double? = null,
    val subscribedPowerP2: Double? = null,
    val subscribedPowerP6: Double? = null,
    val subscribedPowerP3: Double? = null,
    val subscribedPowerP4: Double? = null,
    val subscribedPowerP5: Double? = null,
)

@Serializable
data class ConsumptionPeriodRequest(
    val fechaLecturaInicial: String? = null,
    val fechaLecturaFinal: String? = null,
    val activa: List<Double>? = null,
    val reactiva: List<Double>? = null,
    val maximetro: List<Double>? = null
)

/**
 * Extension function to convert UserConsumptionRequest DTO to UserConsumption domain model.
 * Validates required fields and throws IllegalArgumentException if any are missing.
 */
fun UserConsumptionRequest.toDomainModel(): UserConsumption {
    require(feeType != null) { "feeType is required" }
    require(data != null) { "data is required" }
    require(cups != null) { "cups is required" }
    require(annualConsumption != null) { "annualConsumption is required" }
    require(annualConsumptionP1 != null) { "annualConsumptionP1 is required" }
    require(annualConsumptionP2 != null) { "annualConsumptionP2 is required" }
    require(annualConsumptionP3 != null) { "annualConsumptionP3 is required" }
    require(annualConsumptionP4 != null) { "annualConsumptionP4 is required" }
    require(annualConsumptionP5 != null) { "annualConsumptionP5 is required" }
    require(annualConsumptionP6 != null) { "annualConsumptionP6 is required" }
    require(subscribedPowerP1 != null) { "subscribedPowerP1 is required" }
    require(subscribedPowerP2 != null) { "subscribedPowerP2 is required" }
    require(subscribedPowerP6 != null) { "subscribedPowerP6 is required" }
    require(subscribedPowerP3 != null) { "subscribedPowerP3 is required" }
    require(subscribedPowerP4 != null) { "subscribedPowerP4 is required" }
    require(subscribedPowerP5 != null) { "subscribedPowerP5 is required" }

    return UserConsumption(
        feeType = feeType,
        data = data.map { it.toDomainModel() },
        cups = cups,
        annualConsumption = annualConsumption,
        annualConsumptionP1 = annualConsumptionP1,
        annualConsumptionP2 = annualConsumptionP2,
        annualConsumptionP3 = annualConsumptionP3,
        annualConsumptionP4 = annualConsumptionP4,
        annualConsumptionP5 = annualConsumptionP5,
        annualConsumptionP6 = annualConsumptionP6,
        subscribedPowerP1 = subscribedPowerP1,
        subscribedPowerP2 = subscribedPowerP2,
        subscribedPowerP6 = subscribedPowerP6,
        subscribedPowerP3 = subscribedPowerP3,
        subscribedPowerP4 = subscribedPowerP4,
        subscribedPowerP5 = subscribedPowerP5,
    )
}

/**
 * Extension function to convert ConsumptionPeriodRequest DTO to ConsumptionPeriod domain model.
 */
fun ConsumptionPeriodRequest.toDomainModel(): ConsumptionPeriod {
    require(fechaLecturaInicial != null) { "fechaLecturaInicial is required" }
    require(fechaLecturaFinal != null) { "fechaLecturaFinal is required" }
    require(activa != null) { "activa is required" }
    require(reactiva != null) { "reactiva is required" }
    require(maximetro != null) { "maximetro is required" }

    return ConsumptionPeriod(
        fechaLecturaInicial = fechaLecturaInicial,
        fechaLecturaFinal = fechaLecturaFinal,
        activa = activa,
        reactiva = reactiva,
        maximetro = maximetro
    )
}
