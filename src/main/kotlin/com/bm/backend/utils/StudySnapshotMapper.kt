package com.bm.backend.utils

import com.bm.backend.models.ContractedPowerCheck
import com.bm.backend.models.PeriodValues
import com.bm.backend.models.StudySnapshot
import com.bm.backend.models.StudySnapshotFields
import com.bm.backend.models.StudySnapshotStatus
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

/**
 * Pure, total mapper from the n8n `data.snapshot` contract v1 to [StudySnapshot].
 * Never throws: any defect yields UNAVAILABLE with a single reason code and no retained values.
 * Values are kept as published (no unit conversion, rounding or correction); only a snapshot whose
 * CUPS matches the study's reference CUPS is retained.
 */
object StudySnapshotMapper {
    const val SUPPORTED_VERSION = 1
    const val REASON_NOT_PROVIDED = "not_provided"
    const val REASON_UNSUPPORTED_VERSION = "unsupported_version"
    const val REASON_CUPS_MISMATCH = "cups_mismatch"
    const val REASON_MALFORMED = "malformed"
    val UPSTREAM_REASONS = setOf("not_captured", "parse_error", "cups_mismatch", "invalid_fields")

    private val PERIOD_KEYS = listOf("P1", "P2", "P3", "P4", "P5", "P6")
    private val ISO_DATE: DateTimeFormatter =
        DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT)
    private val CHECKS = mapOf(
        "match" to ContractedPowerCheck.MATCH,
        "mismatch" to ContractedPowerCheck.MISMATCH,
        "not_comparable" to ContractedPowerCheck.NOT_COMPARABLE
    )

    private class Malformed : RuntimeException()

    fun map(element: JsonElement?, referenceCups: String): StudySnapshot {
        if (element == null || element is JsonNull) return StudySnapshot.unavailable(referenceCups, REASON_NOT_PROVIDED)
        return try {
            mapObject(element, referenceCups)
        } catch (e: Exception) {
            StudySnapshot.unavailable(referenceCups, REASON_MALFORMED)
        }
    }

    private fun mapObject(element: JsonElement, referenceCups: String): StudySnapshot {
        val obj = element as? JsonObject ?: throw Malformed()
        val version = obj["version"] as? JsonPrimitive ?: throw Malformed()
        if (version is JsonNull) throw Malformed()
        val versionNumber = StrictJsonNumber.toDouble(version) ?: throw Malformed()
        if (versionNumber != SUPPORTED_VERSION.toDouble()) {
            return StudySnapshot.unavailable(referenceCups, REASON_UNSUPPORTED_VERSION)
        }
        units(obj["units"])

        return when (string(obj["status"])) {
            "unavailable" -> {
                val reason = string(obj["reason"])
                if (reason !in UPSTREAM_REASONS) throw Malformed()
                StudySnapshot.unavailable(referenceCups, reason)
            }
            "available" -> {
                val fieldsObj = obj["fields"] as? JsonObject ?: throw Malformed()
                val check = check(obj["checks"])
                val fields = fields(fieldsObj)
                if (!CupsMatch.same(referenceCups, fields.cups)) {
                    return StudySnapshot.unavailable(referenceCups, REASON_CUPS_MISMATCH)
                }
                StudySnapshot(
                    cups = referenceCups,
                    status = StudySnapshotStatus.AVAILABLE,
                    reason = null,
                    fields = fields.copy(cups = referenceCups),
                    contractedPowerCheck = check
                )
            }
            else -> throw Malformed()
        }
    }

    private fun units(element: JsonElement?) {
        val obj = element as? JsonObject ?: throw Malformed()
        if (string(obj["energy"]) != "kWh" || string(obj["power"]) != "kW") throw Malformed()
    }

    private fun check(element: JsonElement?): ContractedPowerCheck? {
        if (element == null || element is JsonNull) return null
        val obj = element as? JsonObject ?: throw Malformed()
        val value = obj["contractedPowerVsPortal"] ?: return null
        return CHECKS[string(value)] ?: throw Malformed()
    }

    private fun fields(obj: JsonObject): StudySnapshotFields {
        val cups = string(obj["cups"])
        if (cups.isBlank()) throw Malformed()
        return StudySnapshotFields(
            cups = cups,
            distributorCode = optional(obj, "distributorCode", ::string),
            distributorName = optional(obj, "distributorName", ::string),
            postalCode = optional(obj, "postalCode", ::string),
            province = optional(obj, "province", ::string),
            municipality = optional(obj, "municipality", ::string),
            tariff = optional(obj, "tariff", ::string),
            supplyStartDate = optional(obj, "supplyStartDate", ::date),
            lastContractChangeDate = optional(obj, "lastContractChangeDate", ::date),
            lastSupplierChangeDate = optional(obj, "lastSupplierChangeDate", ::date),
            lastReadingDate = optional(obj, "lastReadingDate", ::date),
            annualConsumptionKwh = optional(obj, "annualConsumptionKwh", ::number),
            maxPowerBieKw = optional(obj, "maxPowerBieKw", ::number),
            maxPowerApmKw = optional(obj, "maxPowerApmKw", ::number),
            accessRightsKw = optional(obj, "accessRightsKw", ::number),
            extensionRightsKw = optional(obj, "extensionRightsKw", ::number),
            annualConsumptionPeriodsKwh = optional(obj, "annualConsumptionPeriodsKwh", ::periods),
            contractedPowerKw = optional(obj, "contractedPowerKw", ::periods)
        )
    }

    /** Absent key => null; a present key (including JSON null, which the contract forbids) must parse. */
    private fun <T> optional(obj: JsonObject, key: String, parse: (JsonElement?) -> T): T? =
        if (key in obj) parse(obj[key]) else null

    private fun string(element: JsonElement?): String {
        val primitive = element as? JsonPrimitive ?: throw Malformed()
        if (!primitive.isString) throw Malformed()
        return primitive.content
    }

    private fun number(element: JsonElement?): Double {
        val primitive = element as? JsonPrimitive ?: throw Malformed()
        if (primitive is JsonNull) throw Malformed()
        return StrictJsonNumber.toDouble(primitive) ?: throw Malformed()
    }

    /** Validates strictly and returns the source string unchanged (no time added or dropped). */
    private fun date(element: JsonElement?): String {
        val value = string(element)
        val tIndex = value.indexOf('T')
        if (tIndex < 0) {
            LocalDate.parse(value, ISO_DATE)
        } else {
            LocalDate.parse(value.substring(0, tIndex), ISO_DATE)
            val parsed = runCatching { LocalDateTime.parse(value) }.isSuccess ||
                runCatching { OffsetDateTime.parse(value) }.isSuccess
            if (!parsed) throw Malformed()
        }
        return value
    }

    private fun periods(element: JsonElement?): PeriodValues {
        val obj = element as? JsonObject ?: throw Malformed()
        if (!PERIOD_KEYS.containsAll(obj.keys)) throw Malformed()
        val v = PERIOD_KEYS.map { key -> if (key in obj) number(obj[key]) else null }
        return PeriodValues(v[0], v[1], v[2], v[3], v[4], v[5])
    }
}
