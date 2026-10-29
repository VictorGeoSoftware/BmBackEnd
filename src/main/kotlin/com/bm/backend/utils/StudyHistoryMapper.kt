package com.bm.backend.utils

import com.bm.backend.models.PeriodValues
import com.bm.backend.models.StudyHistory
import com.bm.backend.models.StudyHistoryInterval
import com.bm.backend.models.StudyHistoryStatus
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

/**
 * Pure, total mapper from the n8n `data.history` contract v1 to [StudyHistory].
 * Never throws: any defect yields an UNAVAILABLE history with a single reason code.
 * Values are kept exactly as the JSON numbers (no correction, rounding or reordering).
 */
object StudyHistoryMapper {
    const val SUPPORTED_VERSION = 1
    const val REASON_NOT_PROVIDED = "not_provided"
    const val REASON_UNSUPPORTED_VERSION = "unsupported_version"
    const val REASON_MALFORMED = "malformed"
    val UPSTREAM_REASONS = setOf("missing", "invalid_date", "invalid_measurement")

    private val PERIOD_KEYS = listOf("P1", "P2", "P3", "P4", "P5", "P6")
    private val ISO_DATE: DateTimeFormatter =
        DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT)

    private class Malformed : RuntimeException()

    fun map(element: JsonElement?, cups: String): StudyHistory {
        if (element == null || element is JsonNull) return StudyHistory.unavailable(cups, REASON_NOT_PROVIDED)
        return try {
            mapObject(element, cups)
        } catch (e: Exception) {
            StudyHistory.unavailable(cups, REASON_MALFORMED)
        }
    }

    private fun mapObject(element: JsonElement, cups: String): StudyHistory {
        val obj = element as? JsonObject ?: throw Malformed()
        val version = obj["version"] as? JsonPrimitive ?: throw Malformed()
        val versionNumber = StrictJsonNumber.toInt(version) ?: throw Malformed()
        if (versionNumber != SUPPORTED_VERSION) return StudyHistory.unavailable(cups, REASON_UNSUPPORTED_VERSION)

        return when (string(obj["status"])) {
            "unavailable" -> {
                val reason = string(obj["reason"])
                if (reason !in UPSTREAM_REASONS) throw Malformed()
                StudyHistory.unavailable(cups, reason)
            }
            "available" -> {
                val intervals = obj["intervals"] as? JsonArray ?: throw Malformed()
                StudyHistory(
                    cups = cups,
                    status = StudyHistoryStatus.AVAILABLE,
                    reason = null,
                    intervals = intervals.map(::interval)
                )
            }
            else -> throw Malformed()
        }
    }

    private fun interval(element: JsonElement): StudyHistoryInterval {
        val obj = element as? JsonObject ?: throw Malformed()
        return StudyHistoryInterval(
            startDate = date(obj["startDate"]),
            endDate = date(obj["endDate"]),
            active = periods(obj["active"]),
            reactive = periods(obj["reactive"]),
            maxDemand = periods(obj["maxDemand"])
        )
    }

    private fun string(element: JsonElement?): String {
        val primitive = element as? JsonPrimitive ?: throw Malformed()
        if (!primitive.isString) throw Malformed()
        return primitive.content
    }

    private fun date(element: JsonElement?): LocalDate = LocalDate.parse(string(element), ISO_DATE)

    private fun periods(element: JsonElement?): PeriodValues {
        val obj = element as? JsonObject ?: throw Malformed()
        if (obj.keys != PERIOD_KEYS.toSet()) throw Malformed()
        val v = PERIOD_KEYS.map { value(obj.getValue(it)) }
        return PeriodValues(v[0], v[1], v[2], v[3], v[4], v[5])
    }

    private fun value(element: JsonElement): Double? {
        if (element is JsonNull) return null
        val primitive = element as? JsonPrimitive ?: throw Malformed()
        return StrictJsonNumber.toDouble(primitive) ?: throw Malformed()
    }
}
