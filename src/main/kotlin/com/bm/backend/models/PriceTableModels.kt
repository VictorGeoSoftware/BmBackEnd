package com.bm.backend.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import java.time.Instant

@Serializable
data class StorePriceTablesRequest(
    val filename: String,
    val extracted_tables: ExtractedTables,
    val source: String,
    val timestamp: String? = null
)

@Serializable
data class ExtractedTables(
    val termino_potencia: JsonObject? = null,
    val termino_energia_clasica_base: JsonObject? = null,
    val termino_energia_clasica_unica: JsonObject? = null
)

@Serializable
data class StorePriceTablesResponse(
    val success: Boolean,
    val message: String,
    val record_id: Int
)

@Serializable
data class PriceTableRecord(
    val id: Int,
    val filename: String,
    val extracted_tables: ExtractedTables,
    val source: String,
    val timestamp: String,
    val created_at: String
)

@Serializable
data class PriceTablesResponse(
    val success: Boolean,
    val data: List<PriceTableRecord>,
    val total: Int,
    val limit: Int? = null,
    val offset: Int? = null
)

@Serializable
data class PriceTableDetailResponse(
    val success: Boolean,
    val data: PriceTableRecord
)

@Serializable
data class ErrorResponse(
    val message: String,
    val details: String? = null
)

// New models for batch processing and transposed tables
@Serializable
data class BatchPriceTablesRequest(
    val success: Boolean,
    val results: List<BatchFileResult>
)

@Serializable
data class BatchFileResult(
    val fileName: String,
    val extracted_tables: ExtractedTables
)

// Models for the three transposed price tables
@Serializable
data class PriceRow(
    val fileName: String? = null,
    val tarifa: String?,
    val potencia_contratada: String?,
    val p1: Double?,
    val p2: Double?,
    val p3: Double?,
    val p4: Double?,
    val p5: Double?,
    val p6: Double?
)

@Serializable
data class BatchProcessResponse(
    val success: Boolean,
    val message: String,
    val processed_files: Int,
    val total_rows_inserted: Int
)

// Response models for transposed price table data
@Serializable
data class PriceTableDataResponse(
    val success: Boolean,
    val data: List<PriceRow>,
    val total: Int,
    val limit: Int? = null,
    val offset: Int? = null
)
