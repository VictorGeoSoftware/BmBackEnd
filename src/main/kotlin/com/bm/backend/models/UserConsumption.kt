package com.bm.backend.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class UserConsumption(
    @SerialName("data")
    val data: List<ConsumptionPeriod>
)

@Serializable
data class ConsumptionPeriod(
    @SerialName("Fecha Lectura Inicial")
    val fechaLecturaInicial: String,
    @SerialName("Fecha Lectura Final")
    val fechaLecturaFinal: String,
    @SerialName("Activa")
    val activa: List<Double>,
    @SerialName("Reactiva")
    val reactiva: List<Double>,
    @SerialName("Maximetro")
    val maximetro: List<Double>
)
