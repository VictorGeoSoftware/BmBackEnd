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
    val success: Boolean = false,
    val message: String,
    val details: String? = null
)
