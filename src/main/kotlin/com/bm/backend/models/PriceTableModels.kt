package com.bm.backend.models

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Main response structure
@Serializable
data class PriceTableResponse(
    val success: Boolean,
    val results: List<PriceTableResult>,
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault
    val iva: Int = IVA,
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault
    val impuestoElectrico: Double = IMPUESTO_ELECTRICO
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

@Serializable
data class UploadPriceProposalResponse(
    val success: Boolean,
    val message: String,
    val extracted: PriceTableResponse,
    val storage: BatchProcessResponse
)

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

@Serializable
data class TriggerWorkflowResponse(
    val success: Boolean,
    val message: String,
    val details: String? = null
)

// Filtered response models (for consumption report - single tarifa instead of array)
@Serializable
data class FilteredPriceTableResponse(
    val success: Boolean,
    val results: List<FilteredPriceTableResult>,
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault
    val iva: Int = IVA,
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault
    val impuestoElectrico: Double = IMPUESTO_ELECTRICO
)

@Serializable
data class FilteredPriceTableResult(
    val fileName: String,
    val extracted_tables: FilteredExtractedTables
)

@Serializable
data class FilteredExtractedTables(
    @SerialName("filename") val companyName: String,
    val termino_de_potencia: FilteredTerminoDePotencia,
    val termino_de_energia: FilteredTerminoDeEnergia
)

@Serializable
data class FilteredTerminoDePotencia(
    val titulo: String,
    val tabla_precio_potencia: FilteredTablaPrecioPotencia
)

@Serializable
data class FilteredTablaPrecioPotencia(
    val titulo: String,
    val tarifa: TarifaRow  // Single object instead of list
)

@Serializable
data class FilteredTerminoDeEnergia(
    val titulo: String,
    val tabla_precio_clasica_base: FilteredTablaPrecioClasicaBase,
    val tabla_precio_clasica_unica: FilteredTablaPrecioClasicaUnica
)

@Serializable
data class FilteredTablaPrecioClasicaBase(
    val titulo: String,
    val tarifa: TarifaRow  // Single object instead of list
)

@Serializable
data class FilteredTablaPrecioClasicaUnica(
    val titulo: String,
    val tarifa: TarifaRow  // Single object instead of list
)
