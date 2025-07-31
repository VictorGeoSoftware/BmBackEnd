package com.bm.backend.database

import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.javatime.timestamp
import java.time.Instant

object PriceTableRecords : IntIdTable("price_table_records") {
    val filename = varchar("filename", 255)
    val sourceType = varchar("source", 100)
    val timestampValue = varchar("timestamp", 50)
    val createdAt = timestamp("created_at").default(Instant.now())
    val extractedTables = text("extracted_tables")
}

object TerminoPotencia : IntIdTable("termino_potencia") {
    val recordId = reference("record_id", PriceTableRecords)
    val data = text("data")
    val createdAt = timestamp("created_at").default(Instant.now())
}

object TerminoEnergiaClasicaBase : IntIdTable("termino_energia_clasica_base") {
    val recordId = reference("record_id", PriceTableRecords)
    val data = text("data")
    val createdAt = timestamp("created_at").default(Instant.now())
}

object TerminoEnergiaClasicaUnica : IntIdTable("termino_energia_clasica_unica") {
    val recordId = reference("record_id", PriceTableRecords)
    val data = text("data")
    val createdAt = timestamp("created_at").default(Instant.now())
}
