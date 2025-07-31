package com.bm.backend.services

import com.bm.backend.models.*
import com.bm.backend.repositories.PriceTableRepository
import kotlinx.serialization.json.JsonObject
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

class PriceTableService(private val repository: PriceTableRepository = PriceTableRepository()) {

    fun storePriceTables(request: StorePriceTablesRequest): StorePriceTablesResponse {
        validateRequest(request)
        
        val recordId = repository.storePriceTables(
            filename = request.filename,
            extractedTables = request.extracted_tables,
            source = request.source,
            timestamp = request.timestamp
        )

        return StorePriceTablesResponse(
            success = true,
            message = "Data stored successfully",
            record_id = recordId
        )
    }

    fun getAllPriceTables(
        limit: Int? = null,
        offset: Int? = null,
        filename: String? = null,
        source: String? = null
    ): PriceTablesResponse {
        return repository.getAllPriceTables(limit, offset, filename, source)
    }

    fun getPriceTableById(id: Int): PriceTableDetailResponse? {
        return repository.getPriceTableById(id)?.let { record ->
            PriceTableDetailResponse(
                success = true,
                data = record
            )
        }
    }

    private fun validateRequest(request: StorePriceTablesRequest) {
        if (request.filename.isBlank()) {
            throw ValidationException("Filename cannot be blank")
        }

        if (request.source.isBlank()) {
            throw ValidationException("Source cannot be blank")
        }

        if (request.extracted_tables.termino_potencia == null && 
            request.extracted_tables.termino_energia_clasica_base == null && 
            request.extracted_tables.termino_energia_clasica_unica == null) {
            throw ValidationException("At least one extracted table must be provided")
        }

        request.timestamp?.let { timestamp ->
            try {
                DateTimeFormatter.ISO_INSTANT.parse(timestamp)
            } catch (e: DateTimeParseException) {
                throw ValidationException("Invalid timestamp format. Use ISO 8601 format")
            }
        }
    }
}

class ValidationException(message: String) : RuntimeException(message)
