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

// New transposed price tables
object Prices1 : IntIdTable("prices_1") {
    val fileName = varchar("file_name", 255)
    val tarifa = varchar("tarifa", 50).nullable()
    val potenciaContratada = varchar("potencia_contratada", 100).nullable()
    val p1 = double("p1").nullable()
    val p2 = double("p2").nullable()
    val p3 = double("p3").nullable()
    val p4 = double("p4").nullable()
    val p5 = double("p5").nullable()
    val p6 = double("p6").nullable()
}

object Prices2 : IntIdTable("prices_2") {
    val fileName = varchar("file_name", 255)
    val tarifa = varchar("tarifa", 50).nullable()
    val potenciaContratada = varchar("potencia_contratada", 100).nullable()
    val p1 = double("p1").nullable()
    val p2 = double("p2").nullable()
    val p3 = double("p3").nullable()
    val p4 = double("p4").nullable()
    val p5 = double("p5").nullable()
    val p6 = double("p6").nullable()
}

object Prices3 : IntIdTable("prices_3") {
    val fileName = varchar("file_name", 255)
    val tarifa = varchar("tarifa", 50).nullable()
    val potenciaContratada = varchar("potencia_contratada", 100).nullable()
    val p1 = double("p1").nullable()
    val p2 = double("p2").nullable()
    val p3 = double("p3").nullable()
    val p4 = double("p4").nullable()
    val p5 = double("p5").nullable()
    val p6 = double("p6").nullable()
}
