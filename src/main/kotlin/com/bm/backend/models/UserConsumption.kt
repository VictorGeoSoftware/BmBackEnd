package com.bm.backend.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class UserConsumption(
    val feeType: String,
    @SerialName("data")
    val data: List<ConsumptionPeriod>,
    val cups: String,
    val annualConsumption: Double,
    val annualConsumptionP1: Double,
    val annualConsumptionP2: Double,
    val annualConsumptionP3: Double,
    val annualConsumptionP4: Double,
    val annualConsumptionP5: Double,
    val annualConsumptionP6: Double,
    val subscribedPowerP1: Double,
    val subscribedPowerP2: Double,
    val subscribedPowerP6: Double,
    val subscribedPowerP3: Double,
    val subscribedPowerP4: Double,
    val subscribedPowerP5: Double,
)

@Serializable
data class ConsumptionPeriod(
    val fechaLecturaInicial: String,
    val fechaLecturaFinal: String,
    val activa: List<Double>,
    val reactiva: List<Double>,
    val maximetro: List<Double>
)
