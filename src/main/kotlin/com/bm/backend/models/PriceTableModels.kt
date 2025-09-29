package com.bm.backend.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Main response structure
@Serializable
data class PriceTableResponse(
    val success: Boolean,
    val results: List<PriceTableResult>,
    val iva: Int? = null,
    val impuestoElectrico: Double? = null
)

@Serializable
data class PriceTableResult(
    val fileName: String,
    val extracted_tables: ExtractedTables
)

@Serializable
data class ExtractedTables(
    @SerialName("filename") val companyName: String,
    val termino_de_potencia: TerminoDePotencia,
    val termino_de_energia: TerminoDeEnergia
)

@Serializable
data class TerminoDePotencia(
    val titulo: String,
    val tabla_precio_potencia: TablaPrecioPotencia
)

@Serializable
data class TablaPrecioPotencia(
    val titulo: String,
    val tarifas: List<TarifaRow>
)

@Serializable
data class TerminoDeEnergia(
    val titulo: String,
    val tabla_precio_clasica_base: TablaPrecioClasicaBase,
    val tabla_precio_clasica_unica: TablaPrecioClasicaUnica
)

@Serializable
data class TablaPrecioClasicaBase(
    val titulo: String,
    val tarifas: List<TarifaRow>
)

@Serializable
data class TablaPrecioClasicaUnica(
    val titulo: String,
    val tarifas: List<TarifaRow>
)

@Serializable
data class TarifaRow(
    val tarifa: String,
    val potencia_contratada: String?,
    val P1: Double?,
    val P2: Double?,
    val P3: Double?,
    val P4: Double?,
    val P5: Double?,
    val P6: Double?
)

// Request models for batch processing - accepts array directly
typealias BatchPriceTablesRequest = List<PriceTableResponse>

// Response models
@Serializable
data class BatchProcessResponse(
    val success: Boolean,
    val message: String,
    val processed_files: Int,
    val total_rows_inserted: Int
)

@Serializable
data class ErrorResponse(
    val success: Boolean = false,
    val message: String
)

@Serializable
data class ClearDataResponse(
    val success: Boolean,
    val message: String,
    val deleted_rows: Int
)
