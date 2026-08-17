package com.bm.backend.database

import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.javatime.timestamp

/**
 * Exposed mapping for the `collected_prices` table (Flyway V11).
 *
 * Holds the customer's current electricity prices as entered by a broker, captured
 * when they move on to the price proposals. Contains no customer personal data and
 * no broker identity — only supplier, tariff, prices and collection time.
 *
 * Every period column is nullable because the number of periods depends on the
 * tariff (2.0TD uses fewer than 3.0TD/3.1TD).
 *
 * Schema is owned by the Flyway migration; this object mirrors it by hand. Keep the
 * two in sync — `SchemaUtils.create` is never called in this project.
 */
object CollectedPricesDb : IntIdTable("collected_prices") {
    /** Supplier exactly as the broker typed it. */
    val companyName = varchar("company_name", 255)

    /** Canonical supplier form (trimmed, lowercase, collapsed whitespace) for grouping. */
    val companyNameNormalized = varchar("company_name_normalized", 255)

    /** Tariff as reported by the bill read, e.g. "3.0TD". Stored uppercased and trimmed. */
    val tariffType = varchar("tariff_type", 50)

    val powerP1 = double("power_p1").nullable()
    val powerP2 = double("power_p2").nullable()
    val powerP3 = double("power_p3").nullable()
    val powerP4 = double("power_p4").nullable()
    val powerP5 = double("power_p5").nullable()
    val powerP6 = double("power_p6").nullable()

    val energyP1 = double("energy_p1").nullable()
    val energyP2 = double("energy_p2").nullable()
    val energyP3 = double("energy_p3").nullable()
    val energyP4 = double("energy_p4").nullable()
    val energyP5 = double("energy_p5").nullable()
    val energyP6 = double("energy_p6").nullable()

    val extraServices = double("extra_services").nullable()

    val collectedAt = timestamp("collected_at")
}
