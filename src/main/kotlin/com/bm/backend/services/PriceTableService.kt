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

    fun processBatchPriceTables(request: BatchPriceTablesRequest): BatchProcessResponse {
        var totalRowsInserted = 0
        var processedFiles = 0

        for (fileResult in request.results) {
            try {
                // Process termino_potencia -> prices_1
                fileResult.extracted_tables.termino_potencia?.let { data ->
                    val rows = transposeJsonToRows(data)
                    repository.insertIntoPrices1(rows, fileResult.fileName)
                    totalRowsInserted += rows.size
                }

                // Process termino_energia_clasica_base -> prices_2
                fileResult.extracted_tables.termino_energia_clasica_base?.let { data ->
                    val rows = transposeJsonToRows(data)
                    repository.insertIntoPrices2(rows, fileResult.fileName)
                    totalRowsInserted += rows.size
                }

                // Process termino_energia_clasica_unica -> prices_3
                fileResult.extracted_tables.termino_energia_clasica_unica?.let { data ->
                    val rows = transposeJsonToRows(data)
                    repository.insertIntoPrices3(rows, fileResult.fileName)
                    totalRowsInserted += rows.size
                }

                processedFiles++
            } catch (e: Exception) {
                throw ValidationException("Error processing file ${fileResult.fileName}: ${e.message}")
            }
        }

        return BatchProcessResponse(
            success = true,
            message = "Batch processing completed successfully",
            processed_files = processedFiles,
            total_rows_inserted = totalRowsInserted
        )
    }

    private fun transposeJsonToRows(jsonData: JsonObject): List<PriceRow> {
        val keys = jsonData.keys.toList()
        if (keys.isEmpty()) return emptyList()

        // Get the first array to determine the number of rows
        val firstKey = keys.first()
        val firstArray = jsonData[firstKey]?.let { element ->
            if (element.toString().startsWith("[")) {
                // Parse as array of values
                element.toString().removeSurrounding("[", "]")
                    .split(",")
                    .map { it.trim().removeSurrounding("\"") }
            } else {
                listOf(element.toString().removeSurrounding("\""))
            }
        } ?: emptyList()

        val numRows = firstArray.size
        val rows = mutableListOf<PriceRow>()

        for (rowIndex in 0 until numRows) {
            val row = PriceRow(
                tarifa = getValueAtIndex(jsonData, "TARIFA", rowIndex),
                potencia_contratada = getValueAtIndex(jsonData, "POTENCIA CONTRATADA", rowIndex),
                p1 = getDoubleValueAtIndex(jsonData, "P1", rowIndex),
                p2 = getDoubleValueAtIndex(jsonData, "P2", rowIndex),
                p3 = getDoubleValueAtIndex(jsonData, "P3", rowIndex),
                p4 = getDoubleValueAtIndex(jsonData, "P4", rowIndex),
                p5 = getDoubleValueAtIndex(jsonData, "P5", rowIndex),
                p6 = getDoubleValueAtIndex(jsonData, "P6", rowIndex)
            )
            rows.add(row)
        }

        return rows
    }

    private fun getValueAtIndex(jsonData: JsonObject, key: String, index: Int): String? {
        return jsonData[key]?.let { element ->
            val arrayStr = element.toString()
            if (arrayStr.startsWith("[")) {
                val values = arrayStr.removeSurrounding("[", "]")
                    .split(",")
                    .map { it.trim().removeSurrounding("\"") }
                if (index < values.size) {
                    val value = values[index]
                    if (value == "null") null else value
                } else null
            } else {
                if (index == 0) element.toString().removeSurrounding("\"") else null
            }
        }
    }

    private fun getDoubleValueAtIndex(jsonData: JsonObject, key: String, index: Int): Double? {
        return getValueAtIndex(jsonData, key, index)?.let { value ->
            try {
                value.toDouble()
            } catch (e: NumberFormatException) {
                null
            }
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

    // New methods to fetch transposed table data
    fun getPrices1Data(limit: Int?, offset: Int?, filename: String? = null): PriceTableDataResponse {
        return repository.getPrices1Data(limit, offset, filename)
    }

    fun getPrices2Data(limit: Int?, offset: Int?, filename: String? = null): PriceTableDataResponse {
        return repository.getPrices2Data(limit, offset, filename)
    }

    fun getPrices3Data(limit: Int?, offset: Int?, filename: String? = null): PriceTableDataResponse {
        return repository.getPrices3Data(limit, offset, filename)
    }
}

class ValidationException(message: String) : RuntimeException(message)
