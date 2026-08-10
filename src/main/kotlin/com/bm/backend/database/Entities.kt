package com.bm.backend.database

import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.javatime.timestamp

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

object TaxSettingsDb : IntIdTable("tax_settings") {
    val iva = double("iva")
    val impuestoElectrico = double("impuesto_electrico")
}

// Table for authenticated Firebase user data synced from web login
object UserDataDb : IntIdTable("user_data") {
    val uid = varchar("uid", 128).uniqueIndex()
    val email = varchar("email", 255).nullable()
    val displayName = varchar("display_name", 255).nullable()
    val photoURL = text("photo_url").nullable()
    val providerIds = text("provider_ids")
    // Opaque per-install device identifier used to enforce one-phone-per-account.
    val phoneUuid = varchar("phone_uuid", 64).nullable()
    val tokenIssuedAt = timestamp("token_issued_at")
    val tokenExpiresAt = timestamp("token_expires_at")
    val lastLoginAt = timestamp("last_login_at")
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
}

object UserActivityDb : IntIdTable("user_activity") {
    val email = varchar("email", 255).uniqueIndex()
    val name = varchar("name", 255)
    val isOnline = bool("is_online")
    val monthlyUsageCount = integer("monthly_usage_count")
    val monthKey = varchar("month_key", 7)
    val usageStartedAt = timestamp("usage_started_at").nullable()
    val firstConnectedAt = timestamp("first_connected_at").nullable()
    val lastConnectedAt = timestamp("last_connected_at").nullable()
    val lastDisconnectedAt = timestamp("last_disconnected_at").nullable()
    val updatedAt = timestamp("updated_at")
}

// Table for accounts granted access to the app (replaces the env-var allowlist)
object GrantedUsersDb : IntIdTable("granted_users") {
    val email = varchar("email", 255).uniqueIndex()
    val createdAt = timestamp("created_at")
}

// Table for accounts allowed into the BmWeb dashboard and /admin/* endpoints
object AdminUsersDb : IntIdTable("admin_users") {
    val email = varchar("email", 255).uniqueIndex()
    val createdAt = timestamp("created_at")
}

// Table for persisted user consumption data (JSONB)
object UserConsumptionDb : IntIdTable("user_consumption") {
    val uid = varchar("uid", 128).uniqueIndex()
    val data = text("data") // JSON-serialized UserConsumption
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
}
