package com.bm.backend.utils

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StrictJsonNumberTest {
    private val lenient = Json { isLenient = true }

    private fun raw(token: String): JsonPrimitive =
        lenient.parseToJsonElement("""{"v": $token}""").jsonObject.getValue("v").jsonPrimitive

    @Test
    fun `accepts JSON number grammar unchanged`() {
        assertEquals(15.1, StrictJsonNumber.toDouble(raw("15.1")))
        assertEquals(34.0, StrictJsonNumber.toDouble(raw("34")))
        assertEquals(0.0, StrictJsonNumber.toDouble(raw("0")))
        assertEquals(-2.5, StrictJsonNumber.toDouble(raw("-2.5")))
        assertEquals(1200.0, StrictJsonNumber.toDouble(raw("1.2e3")))
        assertEquals(1, StrictJsonNumber.toInt(raw("1")))
    }

    @Test
    fun `rejects strings, lenient tokens and non-finite values`() {
        listOf("\"15.1\"", "16.1d", "0x10", "1.5f", "NaN", "Infinity", "01", "1.", ".5", "+1", "true", "1e999")
            .forEach { assertNull(StrictJsonNumber.toDouble(raw(it)), it) }
        listOf("1.0", "1e0", "\"1\"", "1d").forEach { assertNull(StrictJsonNumber.toInt(raw(it)), it) }
    }
}
