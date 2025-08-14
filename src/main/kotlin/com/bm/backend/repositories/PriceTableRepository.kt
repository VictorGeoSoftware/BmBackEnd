package com.bm.backend.repositories

import com.bm.backend.database.PriceTableRecords
import com.bm.backend.database.TerminoEnergiaClasicaBase
import com.bm.backend.database.TerminoEnergiaClasicaUnica
import com.bm.backend.database.TerminoPotencia
import com.bm.backend.database.Prices1
import com.bm.backend.database.Prices2
import com.bm.backend.database.Prices3
import com.bm.backend.models.ExtractedTables
import com.bm.backend.models.PriceTableRecord
import com.bm.backend.models.PriceTablesResponse
import com.bm.backend.models.PriceRow
import com.bm.backend.models.PriceTableDataResponse
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction

class PriceTableRepository {
    private val json = Json { ignoreUnknownKeys = true }

    fun storePriceTables(
        filename: String,
        extractedTables: ExtractedTables,
        source: String,
        timestamp: String?
    ): Int {
        return transaction {
            val extractedTablesJson = json.encodeToString(extractedTables)
            
            val recordId = PriceTableRecords.insertAndGetId {
                it[PriceTableRecords.filename] = filename
                it[PriceTableRecords.sourceType] = source
                it[PriceTableRecords.timestampValue] = timestamp ?: java.time.Instant.now().toString()
                it[PriceTableRecords.extractedTables] = extractedTablesJson
            }.value

            // Store individual table data for better querying
            extractedTables.termino_potencia?.let { data ->
                TerminoPotencia.insert {
                    it[TerminoPotencia.recordId] = recordId
                    it[TerminoPotencia.data] = json.encodeToString(data)
                }
            }

            extractedTables.termino_energia_clasica_base?.let { data ->
                TerminoEnergiaClasicaBase.insert {
                    it[TerminoEnergiaClasicaBase.recordId] = recordId
                    it[TerminoEnergiaClasicaBase.data] = json.encodeToString(data)
                }
            }

            extractedTables.termino_energia_clasica_unica?.let { data ->
                TerminoEnergiaClasicaUnica.insert {
                    it[TerminoEnergiaClasicaUnica.recordId] = recordId
                    it[TerminoEnergiaClasicaUnica.data] = json.encodeToString(data)
                }
            }

            recordId
        }
    }

    fun getAllPriceTables(
        limit: Int? = null,
        offset: Int? = null,
        filename: String? = null,
        source: String? = null
    ): PriceTablesResponse {
        return transaction {
            var query = PriceTableRecords.selectAll()

            filename?.let {
                query = query.andWhere { PriceTableRecords.filename like "%$it%" }
            }

            source?.let {
                query = query.andWhere { PriceTableRecords.sourceType eq it }
            }

            val total = query.count()

            limit?.let { lim ->
                query = query.limit(lim, offset?.toLong() ?: 0)
            }

            val data = query.orderBy(PriceTableRecords.id, SortOrder.DESC)
                .map { row ->
                    mapRowToPriceTableRecord(row)
                }

            PriceTablesResponse(
                success = true,
                data = data,
                total = total.toInt(),
                limit = limit,
                offset = offset
            )
        }
    }

    fun getPriceTableById(id: Int): PriceTableRecord? {
        return transaction {
            PriceTableRecords.selectAll().where { PriceTableRecords.id eq id }
                .singleOrNull()
                ?.let { row -> mapRowToPriceTableRecord(row) }
        }
    }

    private fun mapRowToPriceTableRecord(row: ResultRow): PriceTableRecord {
        val extractedTables = json.decodeFromString<ExtractedTables>(row[PriceTableRecords.extractedTables])
        
        return PriceTableRecord(
            id = row[PriceTableRecords.id].value,
            filename = row[PriceTableRecords.filename],
            extracted_tables = extractedTables,
            source = row[PriceTableRecords.sourceType],
            timestamp = row[PriceTableRecords.timestampValue],
            created_at = row[PriceTableRecords.createdAt].toString()
        )
    }

    fun insertIntoPrices1(rows: List<PriceRow>, fileName: String) {
        transaction {
            Prices1.batchInsert(rows) { row ->
                this[Prices1.fileName] = fileName
                this[Prices1.tarifa] = row.tarifa
                this[Prices1.potenciaContratada] = row.potencia_contratada
                this[Prices1.p1] = row.p1
                this[Prices1.p2] = row.p2
                this[Prices1.p3] = row.p3
                this[Prices1.p4] = row.p4
                this[Prices1.p5] = row.p5
                this[Prices1.p6] = row.p6
            }
        }
    }

    fun insertIntoPrices2(rows: List<PriceRow>, fileName: String) {
        transaction {
            Prices2.batchInsert(rows) { row ->
                this[Prices2.fileName] = fileName
                this[Prices2.tarifa] = row.tarifa
                this[Prices2.potenciaContratada] = row.potencia_contratada
                this[Prices2.p1] = row.p1
                this[Prices2.p2] = row.p2
                this[Prices2.p3] = row.p3
                this[Prices2.p4] = row.p4
                this[Prices2.p5] = row.p5
                this[Prices2.p6] = row.p6
            }
        }
    }

    fun insertIntoPrices3(rows: List<PriceRow>, fileName: String) {
        transaction {
            Prices3.batchInsert(rows) { row ->
                this[Prices3.fileName] = fileName
                this[Prices3.tarifa] = row.tarifa
                this[Prices3.potenciaContratada] = row.potencia_contratada
                this[Prices3.p1] = row.p1
                this[Prices3.p2] = row.p2
                this[Prices3.p3] = row.p3
                this[Prices3.p4] = row.p4
                this[Prices3.p5] = row.p5
                this[Prices3.p6] = row.p6
            }
        }
    }

    // New methods to fetch transposed table data
    fun getPrices1Data(limit: Int?, offset: Int?, fileName: String? = null): PriceTableDataResponse {
        return transaction {
            var query = Prices1.selectAll()

            fileName?.let {
                query = query.andWhere { Prices1.fileName eq it }
            }

            val total = query.count().toInt()
            
            val limitedQuery = if (limit != null && offset != null) {
                query.limit(limit, offset.toLong())
            } else if (limit != null) {
                query.limit(limit)
            } else {
                query
            }
            
            val rows = limitedQuery.map { row ->
                PriceRow(
                    fileName = row[Prices1.fileName],
                    tarifa = row[Prices1.tarifa],
                    potencia_contratada = row[Prices1.potenciaContratada],
                    p1 = row[Prices1.p1],
                    p2 = row[Prices1.p2],
                    p3 = row[Prices1.p3],
                    p4 = row[Prices1.p4],
                    p5 = row[Prices1.p5],
                    p6 = row[Prices1.p6]
                )
            }
            
            PriceTableDataResponse(
                success = true,
                data = rows,
                total = total,
                limit = limit,
                offset = offset
            )
        }
    }

    fun getPrices2Data(limit: Int?, offset: Int?, fileName: String? = null): PriceTableDataResponse {
        return transaction {
            var query = Prices2.selectAll()

            fileName?.let {
                query = query.andWhere { Prices2.fileName eq it }
            }

            val total = query.count().toInt()
            
            val limitedQuery = if (limit != null && offset != null) {
                query.limit(limit, offset.toLong())
            } else if (limit != null) {
                query.limit(limit)
            } else {
                query
            }
            
            val rows = limitedQuery.map { row ->
                PriceRow(
                    fileName = row[Prices2.fileName],
                    tarifa = row[Prices2.tarifa],
                    potencia_contratada = row[Prices2.potenciaContratada],
                    p1 = row[Prices2.p1],
                    p2 = row[Prices2.p2],
                    p3 = row[Prices2.p3],
                    p4 = row[Prices2.p4],
                    p5 = row[Prices2.p5],
                    p6 = row[Prices2.p6]
                )
            }
            
            PriceTableDataResponse(
                success = true,
                data = rows,
                total = total,
                limit = limit,
                offset = offset
            )
        }
    }

    fun getPrices3Data(limit: Int?, offset: Int?, fileName: String? = null): PriceTableDataResponse {
        return transaction {
            var query = Prices3.selectAll()

            fileName?.let {
                query = query.andWhere { Prices3.fileName eq it }
            }

            val total = query.count().toInt()
            
            val limitedQuery = if (limit != null && offset != null) {
                query.limit(limit, offset.toLong())
            } else if (limit != null) {
                query.limit(limit)
            } else {
                query
            }
            
            val rows = limitedQuery.map { row ->
                PriceRow(
                    fileName = row[Prices3.fileName],
                    tarifa = row[Prices3.tarifa],
                    potencia_contratada = row[Prices3.potenciaContratada],
                    p1 = row[Prices3.p1],
                    p2 = row[Prices3.p2],
                    p3 = row[Prices3.p3],
                    p4 = row[Prices3.p4],
                    p5 = row[Prices3.p5],
                    p6 = row[Prices3.p6]
                )
            }
            
            PriceTableDataResponse(
                success = true,
                data = rows,
                total = total,
                limit = limit,
                offset = offset
            )
        }
    }

    // New methods for retrieving prices in the new structure
    fun getAllPricesFromTable1(): List<PriceRow> {
        return transaction {
            Prices1.selectAll().map { row ->
                PriceRow(
                    fileName = row[Prices1.fileName],
                    tarifa = row[Prices1.tarifa],
                    potencia_contratada = row[Prices1.potenciaContratada],
                    p1 = row[Prices1.p1],
                    p2 = row[Prices1.p2],
                    p3 = row[Prices1.p3],
                    p4 = row[Prices1.p4],
                    p5 = row[Prices1.p5],
                    p6 = row[Prices1.p6]
                )
            }
        }
    }

    fun getAllPricesFromTable2(): List<PriceRow> {
        return transaction {
            Prices2.selectAll().map { row ->
                PriceRow(
                    fileName = row[Prices2.fileName],
                    tarifa = row[Prices2.tarifa],
                    potencia_contratada = row[Prices2.potenciaContratada],
                    p1 = row[Prices2.p1],
                    p2 = row[Prices2.p2],
                    p3 = row[Prices2.p3],
                    p4 = row[Prices2.p4],
                    p5 = row[Prices2.p5],
                    p6 = row[Prices2.p6]
                )
            }
        }
    }

    fun getAllPricesFromTable3(): List<PriceRow> {
        return transaction {
            Prices3.selectAll().map { row ->
                PriceRow(
                    fileName = row[Prices3.fileName],
                    tarifa = row[Prices3.tarifa],
                    potencia_contratada = row[Prices3.potenciaContratada],
                    p1 = row[Prices3.p1],
                    p2 = row[Prices3.p2],
                    p3 = row[Prices3.p3],
                    p4 = row[Prices3.p4],
                    p5 = row[Prices3.p5],
                    p6 = row[Prices3.p6]
                )
            }
        }
    }
}
