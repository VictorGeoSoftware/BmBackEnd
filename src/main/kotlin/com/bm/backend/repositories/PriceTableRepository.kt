package com.bm.backend.repositories

import com.bm.backend.database.PriceTableRecords
import com.bm.backend.database.TerminoEnergiaClasicaBase
import com.bm.backend.database.TerminoEnergiaClasicaUnica
import com.bm.backend.database.TerminoPotencia
import com.bm.backend.models.ExtractedTables
import com.bm.backend.models.PriceTableRecord
import com.bm.backend.models.PriceTablesResponse
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
}
