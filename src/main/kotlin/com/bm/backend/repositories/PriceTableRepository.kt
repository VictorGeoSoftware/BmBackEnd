package com.bm.backend.repositories

import com.bm.backend.database.*
import com.bm.backend.models.*
import com.bm.backend.repositories.ports.PriceTableRepositoryPort
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory

class PriceTableRepository : PriceTableRepositoryPort {

    private val logger = LoggerFactory.getLogger(PriceTableRepository::class.java)

    override fun getTaxSettings(): TaxSettingsResponse {
        return transaction {
            getOrCreateTaxSettings()
        }
    }

    override fun updateTaxSettings(iva: Double, impuestoElectrico: Double): TaxSettingsResponse {
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

    override fun storePriceTableResults(priceTableResponse: PriceTableResponse): Int {
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

                    // Delete duplicates — CASCADE removes their children automatically
                    existingResultIds.drop(1).forEach { duplicateId ->
                        PriceTableResultsDb.deleteWhere { PriceTableResultsDb.id eq duplicateId }
                    }

                    // Delete children of the kept row by deleting intermediate tables;
                    // CASCADE handles grandchildren (tarifas_*)
                    TerminoDePotenciaDb.deleteWhere { TerminoDePotenciaDb.resultId eq keepResultId }
                    TerminoDeEnergiaDb.deleteWhere { TerminoDeEnergiaDb.resultId eq keepResultId }

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

    override fun getAllPriceTableResults(tarifaType: String?): PriceTableResponse {
        return transaction {
            val results = mutableListOf<PriceTableResult>()
            val taxSettings = getOrCreateTaxSettings()
            
            PriceTableResultsDb.selectAll().forEach { resultRow ->
                val resultId = resultRow[PriceTableResultsDb.id].value
                val fileName = resultRow[PriceTableResultsDb.fileName]
                val companyName = resultRow[PriceTableResultsDb.companyName]
                
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
                
                if (tarifasPotencia.isEmpty() && tarifasEnergiaBase.isEmpty()) {
                    return@forEach
                }
                
                val extractedTables = ExtractedTables(
                    companyName = companyName,
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
    
    override fun getFilteredPriceTableResults(tarifaType: String?): FilteredPriceTableResponse {
        return transaction {
            val results = mutableListOf<FilteredPriceTableResult>()
            val taxSettings = getOrCreateTaxSettings()
            
            PriceTableResultsDb.selectAll().forEach { resultRow ->
                val resultId = resultRow[PriceTableResultsDb.id].value
                val fileName = resultRow[PriceTableResultsDb.fileName]
                val companyName = resultRow[PriceTableResultsDb.companyName]
                
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
                
                if (tarifaPotencia == null && tarifaEnergiaBase == null && tarifaEnergiaUnica == null) {
                    return@forEach
                }
                
                if (tarifaPotencia == null || tarifaEnergiaBase == null || tarifaEnergiaUnica == null) {
                    return@forEach
                }
                
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
    
    override fun clearAllData(): Int {
        return transaction {
            // CASCADE handles all child/grandchild rows automatically
            val priceTableResultsDeleted = PriceTableResultsDb.deleteAll()
            logger.warn("AUDIT: All data cleared — {} price_table_results rows deleted (children cascaded)", priceTableResultsDeleted)
            priceTableResultsDeleted
        }
    }

    override fun deleteResultsByIds(ids: List<Int>): Pair<List<Int>, List<Int>> {
        return transaction {
            val distinctIds = ids.distinct()
            val existingIds = PriceTableResultsDb
                .selectAll()
                .where { PriceTableResultsDb.id inList distinctIds }
                .map { it[PriceTableResultsDb.id].value }
                .toSet()

            val deletedIds = mutableListOf<Int>()
            val notFoundIds = mutableListOf<Int>()

            distinctIds.forEach { id ->
                if (id !in existingIds) {
                    notFoundIds.add(id)
                } else {
                    // CASCADE handles child deletion automatically
                    PriceTableResultsDb.deleteWhere { PriceTableResultsDb.id eq id }
                    deletedIds.add(id)
                }
            }

            logger.info("AUDIT: Selective delete — deleted={}, notFound={}", deletedIds, notFoundIds)
            Pair(deletedIds, notFoundIds)
        }
    }
}
