package com.bm.backend.services

import com.bm.backend.models.BatchPriceTablesRequest
import com.bm.backend.models.BatchProcessResponse
import com.bm.backend.models.ClearDataResponse
import com.bm.backend.models.DeleteSelectedPriceTablesResponse
import com.bm.backend.models.FilteredPriceTableResponse
import com.bm.backend.models.PriceTableResponse
import com.bm.backend.repositories.PriceTableRepository

class PriceTableService(private val repository: PriceTableRepository = PriceTableRepository()) {

    fun processBatchPriceTables(request: BatchPriceTablesRequest): BatchProcessResponse {
        return try {
            // Validate the request first
            if (request.isEmpty()) {
                throw ValidationException("Request cannot be empty")
            }

            // Validate all responses before processing
            for (priceTableResponse in request) {
                validatePriceTableResponse(priceTableResponse)
            }

            var totalRowsInserted = 0
            var processedFiles = 0

            for (priceTableResponse in request) {
                val rowsInserted = repository.storePriceTableResults(priceTableResponse)
                totalRowsInserted += rowsInserted
                processedFiles += priceTableResponse.results.size
            }

            BatchProcessResponse(
                success = true,
                message = "Batch processing completed successfully",
                processed_files = processedFiles,
                total_rows_inserted = totalRowsInserted
            )
        } catch (e: ValidationException) {
            throw e // Re-throw validation exceptions to be handled by the route
        } catch (e: Exception) {
            throw Exception("Error during batch processing: ${e.message}", e)
        }
    }

    fun getAllPriceTableResults(tarifaType: String? = null): PriceTableResponse {
        return repository.getAllPriceTableResults(tarifaType)
    }
    
    fun getFilteredPriceTableResults(tarifaType: String? = null): FilteredPriceTableResponse {
        return repository.getFilteredPriceTableResults(tarifaType)
    }
    
    fun clearAllData(): ClearDataResponse {
        return try {
            val deletedRows = repository.clearAllData()
            ClearDataResponse(
                success = true,
                message = "All data cleared successfully",
                deleted_rows = deletedRows
            )
        } catch (e: Exception) {
            throw Exception("Error clearing database: ${e.message}", e)
        }
    }

    fun deleteSelectedPriceTables(ids: List<Int>): DeleteSelectedPriceTablesResponse {
        if (ids.isEmpty()) {
            throw ValidationException("ids cannot be empty")
        }

        if (ids.any { it <= 0 }) {
            throw ValidationException("ids must contain only positive integers")
        }

        return try {
            val (deletedIds, notFoundIds) = repository.deleteResultsByIds(ids)
            val message = when {
                deletedIds.isNotEmpty() && notFoundIds.isEmpty() -> "Selected price proposals deleted successfully"
                deletedIds.isNotEmpty() -> "Selected price proposals partially deleted"
                else -> "No selected price proposals were deleted"
            }

            DeleteSelectedPriceTablesResponse(
                success = deletedIds.isNotEmpty(),
                message = message,
                deleted_ids = deletedIds,
                not_found_ids = notFoundIds
            )
        } catch (e: Exception) {
            throw Exception("Error deleting selected price proposals: ${e.message}", e)
        }
    }

    private fun validatePriceTableResponse(priceTableResponse: PriceTableResponse) {
        if (priceTableResponse.results.isEmpty()) {
            throw ValidationException("Results cannot be empty")
        }

        priceTableResponse.results.forEach { result ->
            if (result.fileName.isBlank()) {
                throw ValidationException("File name cannot be blank")
            }
            
            if (result.extracted_tables.companyName.isBlank()) {
                throw ValidationException("Company name cannot be blank")
            }

            // Validate termino de potencia structure
            val terminoPotencia = result.extracted_tables.termino_de_potencia
            if (terminoPotencia.titulo.isBlank()) {
                throw ValidationException("Termino de potencia titulo cannot be blank")
            }
            if (terminoPotencia.tabla_precio_potencia.titulo.isBlank()) {
                throw ValidationException("Tabla precio potencia titulo cannot be blank")
            }
            if (terminoPotencia.tabla_precio_potencia.tarifas.isEmpty()) {
                throw ValidationException("Tarifas for termino de potencia cannot be empty")
            }

            // Validate termino de energia structure
            val terminoEnergia = result.extracted_tables.termino_de_energia
            if (terminoEnergia.titulo.isBlank()) {
                throw ValidationException("Termino de energia titulo cannot be blank")
            }
            if (terminoEnergia.tabla_precio_clasica_base.titulo.isBlank()) {
                throw ValidationException("Tabla precio clasica base titulo cannot be blank")
            }
            if (terminoEnergia.tabla_precio_clasica_unica.titulo.isBlank()) {
                throw ValidationException("Tabla precio clasica unica titulo cannot be blank")
            }
            if (terminoEnergia.tabla_precio_clasica_base.tarifas.isEmpty()) {
                throw ValidationException("Tarifas for tabla precio clasica base cannot be empty")
            }
            if (terminoEnergia.tabla_precio_clasica_unica.tarifas.isEmpty()) {
                throw ValidationException("Tarifas for tabla precio clasica unica cannot be empty")
            }

            // Validate tarifa rows
            val allTarifas = terminoPotencia.tabla_precio_potencia.tarifas + 
                           terminoEnergia.tabla_precio_clasica_base.tarifas + 
                           terminoEnergia.tabla_precio_clasica_unica.tarifas

            allTarifas.forEach { tarifa ->
                if (tarifa.tarifa.isBlank()) {
                    throw ValidationException("Tarifa name cannot be blank")
                }
            }
        }
    }
}

class ValidationException(message: String) : Exception(message)
