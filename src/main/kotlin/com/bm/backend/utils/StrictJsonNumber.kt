package com.bm.backend.utils

import kotlinx.serialization.json.JsonPrimitive

/**
 * Strict JSON number parsing. The n8n response is decoded leniently, so unquoted tokens such as
 * `16.1d` or `0x10` can arrive as non-string primitives; only RFC 8259 number tokens are accepted.
 */
object StrictJsonNumber {
    private val GRAMMAR = Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")

    /** Finite double for a strict JSON number primitive, otherwise null (strings, literals, lenient tokens). */
    fun toDouble(primitive: JsonPrimitive): Double? {
        if (primitive.isString) return null
        val content = primitive.content
        if (!GRAMMAR.matches(content)) return null
        return content.toDouble().takeIf { it.isFinite() }
    }

    /** Integer value for a strict JSON number primitive without fraction/exponent, otherwise null. */
    fun toInt(primitive: JsonPrimitive): Int? {
        if (primitive.isString) return null
        val content = primitive.content
        if (!GRAMMAR.matches(content)) return null
        return content.toIntOrNull()
    }
}
