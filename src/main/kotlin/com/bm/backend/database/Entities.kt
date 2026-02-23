package com.bm.backend.database

import org.jetbrains.exposed.dao.id.IntIdTable

// Main table for storing file results
object PriceTableResultsDb : IntIdTable("price_table_results") {
    val fileName = varchar("file_name", 255)
    val companyName = varchar("company_name", 255)
}

// Table for termino de potencia data
object TerminoDePotenciaDb : IntIdTable("termino_de_potencia") {
    val resultId = reference("result_id", PriceTableResultsDb)
    val titulo = text("titulo")
    val tablaTitulo = text("tabla_titulo")
}

// Table for termino de energia data
object TerminoDeEnergiaDb : IntIdTable("termino_de_energia") {
    val resultId = reference("result_id", PriceTableResultsDb)
    val titulo = text("titulo")
    val tablaBaseTitulo = text("tabla_base_titulo")
    val tablaUnicaTitulo = text("tabla_unica_titulo")
}

// Table for tarifa rows from termino de potencia
object TarifasPotenciaDb : IntIdTable("tarifas_potencia") {
    val terminoId = reference("termino_id", TerminoDePotenciaDb)
    val tarifa = varchar("tarifa", 50)
    val potenciaContratada = varchar("potencia_contratada", 100).nullable()
    val p1 = double("p1").nullable()
    val p2 = double("p2").nullable()
    val p3 = double("p3").nullable()
    val p4 = double("p4").nullable()
    val p5 = double("p5").nullable()
    val p6 = double("p6").nullable()
}

// Table for tarifa rows from termino de energia base
object TarifasEnergiaBaseDb : IntIdTable("tarifas_energia_base") {
    val terminoId = reference("termino_id", TerminoDeEnergiaDb)
    val tarifa = varchar("tarifa", 50)
    val potenciaContratada = varchar("potencia_contratada", 100).nullable()
    val p1 = double("p1").nullable()
    val p2 = double("p2").nullable()
    val p3 = double("p3").nullable()
    val p4 = double("p4").nullable()
    val p5 = double("p5").nullable()
    val p6 = double("p6").nullable()
}

// Table for tarifa rows from termino de energia unica
object TarifasEnergiaUnicaDb : IntIdTable("tarifas_energia_unica") {
    val terminoId = reference("termino_id", TerminoDeEnergiaDb)
    val tarifa = varchar("tarifa", 50)
    val potenciaContratada = varchar("potencia_contratada", 100).nullable()
    val p1 = double("p1").nullable()
    val p2 = double("p2").nullable()
    val p3 = double("p3").nullable()
    val p4 = double("p4").nullable()
    val p5 = double("p5").nullable()
    val p6 = double("p6").nullable()
}

// Table for authenticated Firebase user data synced from web login
object UserDataDb : IntIdTable("user_data") {
    val uid = varchar("uid", 128).uniqueIndex()
    val email = varchar("email", 255).nullable()
    val displayName = varchar("display_name", 255).nullable()
    val photoURL = text("photo_url").nullable()
    val providerIds = text("provider_ids")
    val tokenIssuedAt = long("token_issued_at")
    val tokenExpiresAt = long("token_expires_at")
    val lastLoginAt = long("last_login_at")
    val createdAt = long("created_at")
    val updatedAt = long("updated_at")
}
