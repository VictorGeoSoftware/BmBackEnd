package com.bm.backend.repositories

import com.bm.backend.database.*
import com.bm.backend.models.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory

class PriceTableRepository {

    private val logger = LoggerFactory.getLogger(PriceTableRepository::class.java)

    fun getTaxSettings(): TaxSettingsResponse {
        return transaction {
            getOrCreateTaxSettings()
        }
    }

    fun updateTaxSettings(iva: Double, impuestoElectrico: Double): TaxSettingsResponse {
        return transaction {
            val existingRow = TaxSettingsDb
                .selectAll()
                .orderBy(TaxSettingsDb.id to SortOrder.ASC)
                .firstOrNull()

            if (existingRow == null) {
                TaxSettingsDb.insert {
                    it[TaxSettingsDb.iva] = iva
                    it[TaxSettingsDb.impuestoElectrico] = impuestoElectrico
                }
            } else {
                TaxSettingsDb.update({ TaxSettingsDb.id eq existingRow[TaxSettingsDb.id].value }) {
                    it[TaxSettingsDb.iva] = iva
                    it[TaxSettingsDb.impuestoElectrico] = impuestoElectrico
                }
            }

            TaxSettingsResponse(
                success = true,
                iva = iva,
                impuestoElectrico = impuestoElectrico
            )
        }
    }

    private fun getOrCreateTaxSettings(): TaxSettingsResponse {
        val existingRow = TaxSettingsDb
            .selectAll()
            .orderBy(TaxSettingsDb.id to SortOrder.ASC)
            .firstOrNull()

        if (existingRow != null) {
            return TaxSettingsResponse(
                success = true,
                iva = existingRow[TaxSettingsDb.iva],
                impuestoElectrico = existingRow[TaxSettingsDb.impuestoElectrico]
            )
        }

        TaxSettingsDb.insert {
            it[iva] = IVA
            it[impuestoElectrico] = IMPUESTO_ELECTRICO
        }

        return TaxSettingsResponse(
            success = true,
            iva = IVA,
            impuestoElectrico = IMPUESTO_ELECTRICO
        )
    }

    fun storePriceTableResults(priceTableResponse: PriceTableResponse): Int {
        return transaction {
            var totalRowsInserted = 0
            
            priceTableResponse.results.forEach { result ->
                val existingResultIds = findResultIdsByNaturalKey(
                    fileName = result.fileName,
                    companyName = result.extracted_tables.companyName
                )

                if (existingResultIds.isNotEmpty()) {
                    val keepResultId = existingResultIds.first()

                    PriceTableResultsDb.update({ PriceTableResultsDb.id eq keepResultId }) {
                        it[fileName] = result.fileName
                        it[companyName] = result.extracted_tables.companyName
                    }

                    existingResultIds.drop(1).forEach { duplicateId ->
                        deleteResultById(duplicateId)
                    }

                    deleteChildrenByResultId(keepResultId)
                    totalRowsInserted += insertPriceTableDetails(keepResultId, result)
                    return@forEach
                }

                // Insert main result record
                val resultId = PriceTableResultsDb.insertAndGetId {
                    it[PriceTableResultsDb.fileName] = result.fileName
                    it[PriceTableResultsDb.companyName] = result.extracted_tables.companyName
                }.value

                totalRowsInserted += insertPriceTableDetails(resultId, result)
            }
            
            logger.info("AUDIT: Batch store completed — {} rows inserted from {} results", totalRowsInserted, priceTableResponse.results.size)
            totalRowsInserted
        }
    }

    private fun insertPriceTableDetails(resultId: Int, result: PriceTableResult): Int {
        var insertedRows = 0

        // Insert termino de potencia
        val terminoPotenciaId = TerminoDePotenciaDb.insertAndGetId {
            it[TerminoDePotenciaDb.resultId] = resultId
            it[TerminoDePotenciaDb.titulo] = result.extracted_tables.termino_de_potencia.titulo
            it[TerminoDePotenciaDb.tablaTitulo] = result.extracted_tables.termino_de_potencia.tabla_precio_potencia.titulo
        }.value

        // Insert tarifa rows for potencia
        result.extracted_tables.termino_de_potencia.tabla_precio_potencia.tarifas.forEach { tarifa ->
            TarifasPotenciaDb.insert {
                it[TarifasPotenciaDb.terminoId] = terminoPotenciaId
                it[TarifasPotenciaDb.tarifa] = tarifa.tarifa
                it[TarifasPotenciaDb.potenciaContratada] = tarifa.potencia_contratada
                it[TarifasPotenciaDb.p1] = tarifa.P1
                it[TarifasPotenciaDb.p2] = tarifa.P2
                it[TarifasPotenciaDb.p3] = tarifa.P3
                it[TarifasPotenciaDb.p4] = tarifa.P4
                it[TarifasPotenciaDb.p5] = tarifa.P5
                it[TarifasPotenciaDb.p6] = tarifa.P6
            }
            insertedRows++
        }

        // Insert termino de energia
        val terminoEnergiaId = TerminoDeEnergiaDb.insertAndGetId {
            it[TerminoDeEnergiaDb.resultId] = resultId
            it[TerminoDeEnergiaDb.titulo] = result.extracted_tables.termino_de_energia.titulo
            it[TerminoDeEnergiaDb.tablaBaseTitulo] = result.extracted_tables.termino_de_energia.tabla_precio_clasica_base.titulo
            it[TerminoDeEnergiaDb.tablaUnicaTitulo] = result.extracted_tables.termino_de_energia.tabla_precio_clasica_unica.titulo
        }.value

        // Insert tarifa rows for energia base
        result.extracted_tables.termino_de_energia.tabla_precio_clasica_base.tarifas.forEach { tarifa ->
            TarifasEnergiaBaseDb.insert {
                it[TarifasEnergiaBaseDb.terminoId] = terminoEnergiaId
                it[TarifasEnergiaBaseDb.tarifa] = tarifa.tarifa
                it[TarifasEnergiaBaseDb.potenciaContratada] = tarifa.potencia_contratada
                it[TarifasEnergiaBaseDb.p1] = tarifa.P1
                it[TarifasEnergiaBaseDb.p2] = tarifa.P2
                it[TarifasEnergiaBaseDb.p3] = tarifa.P3
                it[TarifasEnergiaBaseDb.p4] = tarifa.P4
                it[TarifasEnergiaBaseDb.p5] = tarifa.P5
                it[TarifasEnergiaBaseDb.p6] = tarifa.P6
            }
            insertedRows++
        }

        // Insert tarifa rows for energia unica
        result.extracted_tables.termino_de_energia.tabla_precio_clasica_unica.tarifas.forEach { tarifa ->
            TarifasEnergiaUnicaDb.insert {
                it[TarifasEnergiaUnicaDb.terminoId] = terminoEnergiaId
                it[TarifasEnergiaUnicaDb.tarifa] = tarifa.tarifa
                it[TarifasEnergiaUnicaDb.potenciaContratada] = tarifa.potencia_contratada
                it[TarifasEnergiaUnicaDb.p1] = tarifa.P1
                it[TarifasEnergiaUnicaDb.p2] = tarifa.P2
                it[TarifasEnergiaUnicaDb.p3] = tarifa.P3
                it[TarifasEnergiaUnicaDb.p4] = tarifa.P4
                it[TarifasEnergiaUnicaDb.p5] = tarifa.P5
                it[TarifasEnergiaUnicaDb.p6] = tarifa.P6
            }
            insertedRows++
        }

        return insertedRows
    }

    private fun findResultIdsByNaturalKey(fileName: String, companyName: String): List<Int> {
        return PriceTableResultsDb
            .selectAll()
            .where {
                (PriceTableResultsDb.fileName eq fileName) and
                    (PriceTableResultsDb.companyName eq companyName)
            }
            .map { it[PriceTableResultsDb.id].value }
    }

    private fun deleteChildrenByResultId(resultId: Int) {
        val potenciaIds = TerminoDePotenciaDb
            .selectAll()
            .where { TerminoDePotenciaDb.resultId eq resultId }
            .map { it[TerminoDePotenciaDb.id].value }

        potenciaIds.forEach { terminoId ->
            TarifasPotenciaDb.deleteWhere { TarifasPotenciaDb.terminoId eq terminoId }
        }
        TerminoDePotenciaDb.deleteWhere { TerminoDePotenciaDb.resultId eq resultId }

        val energiaIds = TerminoDeEnergiaDb
            .selectAll()
            .where { TerminoDeEnergiaDb.resultId eq resultId }
            .map { it[TerminoDeEnergiaDb.id].value }

        energiaIds.forEach { terminoId ->
            TarifasEnergiaBaseDb.deleteWhere { TarifasEnergiaBaseDb.terminoId eq terminoId }
            TarifasEnergiaUnicaDb.deleteWhere { TarifasEnergiaUnicaDb.terminoId eq terminoId }
        }
        TerminoDeEnergiaDb.deleteWhere { TerminoDeEnergiaDb.resultId eq resultId }
    }

    private fun deleteResultById(resultId: Int): Boolean {
        val deletedRows = PriceTableResultsDb
            .selectAll()
            .where { PriceTableResultsDb.id eq resultId }
            .count()

        if (deletedRows == 0L) {
            return false
        }

        deleteChildrenByResultId(resultId)
        PriceTableResultsDb.deleteWhere { PriceTableResultsDb.id eq resultId }
        return true
    }
    
    fun getAllPriceTableResults(tarifaType: String? = null): PriceTableResponse {
        return transaction {
            val results = mutableListOf<PriceTableResult>()
            val taxSettings = getOrCreateTaxSettings()
            
            // Get all main results
            PriceTableResultsDb.selectAll().forEach { resultRow ->
                val resultId = resultRow[PriceTableResultsDb.id].value
                val fileName = resultRow[PriceTableResultsDb.fileName]
                val companyName = resultRow[PriceTableResultsDb.companyName]
                
                // Get termino de potencia data
                val terminoPotenciaRow = TerminoDePotenciaDb.selectAll().where { TerminoDePotenciaDb.resultId eq resultId }.single()
                val terminoPotenciaId = terminoPotenciaRow[TerminoDePotenciaDb.id].value
                
                val tarifasPotencia = TarifasPotenciaDb.selectAll().where { TarifasPotenciaDb.terminoId eq terminoPotenciaId }
                    .map { row ->
                        TarifaRow(
                            tarifa = row[TarifasPotenciaDb.tarifa],
                            potencia_contratada = row[TarifasPotenciaDb.potenciaContratada],
                            P1 = row[TarifasPotenciaDb.p1],
                            P2 = row[TarifasPotenciaDb.p2],
                            P3 = row[TarifasPotenciaDb.p3],
                            P4 = row[TarifasPotenciaDb.p4],
                            P5 = row[TarifasPotenciaDb.p5],
                            P6 = row[TarifasPotenciaDb.p6]
                        )
                    }
                    .let { tarifas ->
                        if (tarifaType != null) {
                            tarifas.filter { it.tarifa.equals(tarifaType, ignoreCase = true) }
                        } else {
                            tarifas
                        }
                    }
                
                // Get termino de energia data
                val terminoEnergiaRow = TerminoDeEnergiaDb.selectAll().where { TerminoDeEnergiaDb.resultId eq resultId }.single()
                val terminoEnergiaId = terminoEnergiaRow[TerminoDeEnergiaDb.id].value
                
                val tarifasEnergiaBase = TarifasEnergiaBaseDb.selectAll().where { TarifasEnergiaBaseDb.terminoId eq terminoEnergiaId }
                    .map { row ->
                        TarifaRow(
                            tarifa = row[TarifasEnergiaBaseDb.tarifa],
                            potencia_contratada = row[TarifasEnergiaBaseDb.potenciaContratada],
                            P1 = row[TarifasEnergiaBaseDb.p1],
                            P2 = row[TarifasEnergiaBaseDb.p2],
                            P3 = row[TarifasEnergiaBaseDb.p3],
                            P4 = row[TarifasEnergiaBaseDb.p4],
                            P5 = row[TarifasEnergiaBaseDb.p5],
                            P6 = row[TarifasEnergiaBaseDb.p6]
                        )
                    }
                    .let { tarifas ->
                        if (tarifaType != null) {
                            tarifas.filter { it.tarifa.equals(tarifaType, ignoreCase = true) }
                        } else {
                            tarifas
                        }
                    }
                
                // Skip this result if all tarifa lists are empty after filtering
                if (tarifasPotencia.isEmpty() && tarifasEnergiaBase.isEmpty()) {
                    return@forEach
                }
                
                // Build the result structure
                val extractedTables = ExtractedTables(
                    companyName = companyName, // Use the company name from the result
                    termino_de_potencia = TerminoDePotencia(
                        titulo = terminoPotenciaRow[TerminoDePotenciaDb.titulo],
                        tabla_precio_potencia = TablaPrecioPotencia(
                            titulo = terminoPotenciaRow[TerminoDePotenciaDb.tablaTitulo],
                            tarifas = tarifasPotencia
                        )
                    ),
                    termino_de_energia = TerminoDeEnergia(
                        titulo = terminoEnergiaRow[TerminoDeEnergiaDb.titulo],
                        tabla_precio_clasica_base = TablaPrecioClasicaBase(
                            titulo = terminoEnergiaRow[TerminoDeEnergiaDb.tablaBaseTitulo],
                            tarifas = tarifasEnergiaBase
                        ),
                        tabla_precio_clasica_unica = TablaPrecioClasicaUnica(
                            titulo = terminoEnergiaRow[TerminoDeEnergiaDb.tablaUnicaTitulo],
                            tarifas = emptyList()
                        )
                    )
                )
                
                results.add(
                    PriceTableResult(
                        id = resultId,
                        fileName = fileName,
                        extracted_tables = extractedTables
                    )
                )
            }
            
            PriceTableResponse(
                success = true,
                results = results,
                iva = taxSettings.iva,
                impuestoElectrico = taxSettings.impuestoElectrico,
            )
        }
    }
    
    fun getFilteredPriceTableResults(tarifaType: String? = null): FilteredPriceTableResponse {
        return transaction {
            val results = mutableListOf<FilteredPriceTableResult>()
            val taxSettings = getOrCreateTaxSettings()
            
            // Get all main results
            PriceTableResultsDb.selectAll().forEach { resultRow ->
                val resultId = resultRow[PriceTableResultsDb.id].value
                val fileName = resultRow[PriceTableResultsDb.fileName]
                val companyName = resultRow[PriceTableResultsDb.companyName]
                
                // Get termino de potencia data
                val terminoPotenciaRow = TerminoDePotenciaDb.selectAll().where { TerminoDePotenciaDb.resultId eq resultId }.single()
                val terminoPotenciaId = terminoPotenciaRow[TerminoDePotenciaDb.id].value
                
                val tarifaPotencia = TarifasPotenciaDb.selectAll().where { TarifasPotenciaDb.terminoId eq terminoPotenciaId }
                    .map { row ->
                        TarifaRow(
                            tarifa = row[TarifasPotenciaDb.tarifa],
                            potencia_contratada = row[TarifasPotenciaDb.potenciaContratada],
                            P1 = row[TarifasPotenciaDb.p1],
                            P2 = row[TarifasPotenciaDb.p2],
                            P3 = row[TarifasPotenciaDb.p3],
                            P4 = row[TarifasPotenciaDb.p4],
                            P5 = row[TarifasPotenciaDb.p5],
                            P6 = row[TarifasPotenciaDb.p6]
                        )
                    }
                    .let { tarifas ->
                        if (tarifaType != null) {
                            tarifas.filter { it.tarifa.equals(tarifaType, ignoreCase = true) }
                        } else {
                            tarifas
                        }
                    }
                    .firstOrNull()
                
                // Get termino de energia data
                val terminoEnergiaRow = TerminoDeEnergiaDb.selectAll().where { TerminoDeEnergiaDb.resultId eq resultId }.single()
                val terminoEnergiaId = terminoEnergiaRow[TerminoDeEnergiaDb.id].value
                
                val tarifaEnergiaBase = TarifasEnergiaBaseDb.selectAll().where { TarifasEnergiaBaseDb.terminoId eq terminoEnergiaId }
                    .map { row ->
                        TarifaRow(
                            tarifa = row[TarifasEnergiaBaseDb.tarifa],
                            potencia_contratada = row[TarifasEnergiaBaseDb.potenciaContratada],
                            P1 = row[TarifasEnergiaBaseDb.p1],
                            P2 = row[TarifasEnergiaBaseDb.p2],
                            P3 = row[TarifasEnergiaBaseDb.p3],
                            P4 = row[TarifasEnergiaBaseDb.p4],
                            P5 = row[TarifasEnergiaBaseDb.p5],
                            P6 = row[TarifasEnergiaBaseDb.p6]
                        )
                    }
                    .let { tarifas ->
                        if (tarifaType != null) {
                            tarifas.filter { it.tarifa.equals(tarifaType, ignoreCase = true) }
                        } else {
                            tarifas
                        }
                    }
                    .firstOrNull()
                
                val tarifaEnergiaUnica = TarifasEnergiaUnicaDb.selectAll().where { TarifasEnergiaUnicaDb.terminoId eq terminoEnergiaId }
                    .map { row ->
                        TarifaRow(
                            tarifa = row[TarifasEnergiaUnicaDb.tarifa],
                            potencia_contratada = row[TarifasEnergiaUnicaDb.potenciaContratada],
                            P1 = row[TarifasEnergiaUnicaDb.p1],
                            P2 = row[TarifasEnergiaUnicaDb.p2],
                            P3 = row[TarifasEnergiaUnicaDb.p3],
                            P4 = row[TarifasEnergiaUnicaDb.p4],
                            P5 = row[TarifasEnergiaUnicaDb.p5],
                            P6 = row[TarifasEnergiaUnicaDb.p6]
                        )
                    }
                    .let { tarifas ->
                        if (tarifaType != null) {
                            tarifas.filter { it.tarifa.equals(tarifaType, ignoreCase = true) }
                        } else {
                            tarifas
                        }
                    }
                    .firstOrNull()
                
                // Skip this result if all tarifa objects are null after filtering
                if (tarifaPotencia == null && tarifaEnergiaBase == null && tarifaEnergiaUnica == null) {
                    return@forEach
                }
                
                // Skip if any required tarifa is missing (all three should exist)
                if (tarifaPotencia == null || tarifaEnergiaBase == null || tarifaEnergiaUnica == null) {
                    return@forEach
                }
                
                // Build the result structure with single tarifa objects
                val extractedTables = FilteredExtractedTables(
                    companyName = companyName,
                    termino_de_potencia = FilteredTerminoDePotencia(
                        titulo = terminoPotenciaRow[TerminoDePotenciaDb.titulo],
                        tabla_precio_potencia = FilteredTablaPrecioPotencia(
                            titulo = terminoPotenciaRow[TerminoDePotenciaDb.tablaTitulo],
                            tarifa = tarifaPotencia
                        )
                    ),
                    termino_de_energia = FilteredTerminoDeEnergia(
                        titulo = terminoEnergiaRow[TerminoDeEnergiaDb.titulo],
                        tabla_precio_clasica_base = FilteredTablaPrecioClasicaBase(
                            titulo = terminoEnergiaRow[TerminoDeEnergiaDb.tablaBaseTitulo],
                            tarifa = tarifaEnergiaBase
                        ),
                        tabla_precio_clasica_unica = FilteredTablaPrecioClasicaUnica(
                            titulo = terminoEnergiaRow[TerminoDeEnergiaDb.tablaUnicaTitulo],
                            tarifa = tarifaEnergiaUnica
                        )
                    )
                )
                
                results.add(FilteredPriceTableResult(fileName = fileName, extracted_tables = extractedTables))
            }
            
            FilteredPriceTableResponse(
                success = true,
                results = results,
                iva = taxSettings.iva,
                impuestoElectrico = taxSettings.impuestoElectrico,
            )
        }
    }
    
    fun clearAllData(): Int {
        return transaction {
            // Delete in reverse order of dependencies to avoid foreign key constraint violations
            val tarifasPotenciaDeleted = TarifasPotenciaDb.deleteAll()
            val tarifasEnergiaBaseDeleted = TarifasEnergiaBaseDb.deleteAll()
            val tarifasEnergiaUnicaDeleted = TarifasEnergiaUnicaDb.deleteAll()
            val terminoPotenciaDeleted = TerminoDePotenciaDb.deleteAll()
            val terminoEnergiaDeleted = TerminoDeEnergiaDb.deleteAll()
            val priceTableResultsDeleted = PriceTableResultsDb.deleteAll()
            
            // Return total number of rows deleted
            val total = tarifasPotenciaDeleted + tarifasEnergiaBaseDeleted + tarifasEnergiaUnicaDeleted + 
            terminoPotenciaDeleted + terminoEnergiaDeleted + priceTableResultsDeleted

            logger.warn("AUDIT: All data cleared — {} total rows deleted", total)
            total
        }
    }

    fun deleteResultsByIds(ids: List<Int>): Pair<List<Int>, List<Int>> {
        return transaction {
            val distinctIds = ids.distinct()
            val existingIds = PriceTableResultsDb
                .selectAll()
                .map { it[PriceTableResultsDb.id].value }
                .toSet()

            val deletedIds = mutableListOf<Int>()
            val notFoundIds = mutableListOf<Int>()

            distinctIds.forEach { id ->
                if (id !in existingIds) {
                    notFoundIds.add(id)
                    return@forEach
                }

                val deleted = deleteResultById(id)
                if (deleted) {
                    deletedIds.add(id)
                } else {
                    notFoundIds.add(id)
                }
            }

            logger.info("AUDIT: Selective delete — deleted={}, notFound={}", deletedIds, notFoundIds)
            Pair(deletedIds, notFoundIds)
        }
    }
}
