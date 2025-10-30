package com.bm.backend.repositories

import com.bm.backend.database.*
import com.bm.backend.models.*
import com.bm.backend.models.BatchPriceTablesRequest
import com.bm.backend.models.BatchProcessResponse
import com.bm.backend.models.ClearDataResponse
import com.bm.backend.models.ExtractedTables
import com.bm.backend.models.PriceTableResponse
import com.bm.backend.models.PriceTableResult
import com.bm.backend.models.TablaPrecioClasicaBase
import com.bm.backend.models.TablaPrecioClasicaUnica
import com.bm.backend.models.TablaPrecioPotencia
import com.bm.backend.models.TarifaRow
import com.bm.backend.models.TerminoDeEnergia
import com.bm.backend.models.TerminoDePotencia
import com.bm.backend.models.IMPUESTO_ELECTRICO
import com.bm.backend.models.IVA
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

class PriceTableRepository {

    fun storePriceTableResults(priceTableResponse: PriceTableResponse): Int {
        return transaction {
            var totalRowsInserted = 0
            
            priceTableResponse.results.forEach { result ->
                // Insert main result record
                val resultId = PriceTableResultsDb.insertAndGetId {
                    it[PriceTableResultsDb.fileName] = result.fileName
                    it[PriceTableResultsDb.companyName] = result.extracted_tables.companyName
                }.value
                
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
                    totalRowsInserted++
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
                    totalRowsInserted++
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
                    totalRowsInserted++
                }
            }
            
            totalRowsInserted
        }
    }
    
    fun getAllPriceTableResults(tarifaType: String? = null): PriceTableResponse {
        return transaction {
            val results = mutableListOf<PriceTableResult>()
            
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
                
                val tarifasEnergiaUnica = TarifasEnergiaUnicaDb.selectAll().where { TarifasEnergiaUnicaDb.terminoId eq terminoEnergiaId }
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
                
                // Skip this result if all tarifa lists are empty after filtering
                if (tarifasPotencia.isEmpty() && tarifasEnergiaBase.isEmpty() && tarifasEnergiaUnica.isEmpty()) {
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
                            tarifas = tarifasEnergiaUnica
                        )
                    )
                )
                
                results.add(PriceTableResult(fileName = fileName, extracted_tables = extractedTables))
            }
            
            PriceTableResponse(
                success = true,
                results = results,
                iva = IVA,
                impuestoElectrico = IMPUESTO_ELECTRICO,
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
            tarifasPotenciaDeleted + tarifasEnergiaBaseDeleted + tarifasEnergiaUnicaDeleted + 
            terminoPotenciaDeleted + terminoEnergiaDeleted + priceTableResultsDeleted
        }
    }
}
