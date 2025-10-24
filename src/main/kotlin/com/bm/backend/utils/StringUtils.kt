package com.bm.backend.utils

/**
 * Removes all characters except letters, numbers, and dots from a string.
 * Useful for cleaning data that may contain unwanted prefixes or special characters.
 */
fun String.cleanAndConvert(): String {
    return this.replace(Regex("[^a-zA-Z0-9.]"), "")
}

/**
 * Cleans a string and converts it to a Double.
 * Returns 0.0 if the conversion fails.
 */
fun String.cleanAndConvertToDouble(): Double {
    val cleaned = this.cleanAndConvert()
    return cleaned.toDoubleOrNull() ?: 0.0
}
