package com.bm.backend.models

/**
 * Validator for Spanish CUPS (Código Universal de Punto de Suministro).
 *
 * Format (per Real Decreto 1110/2007 — see Naturgy reference article):
 *   ES + DDDD + AAAAAAAAAAAA + CC [+ FF]
 *
 * - "ES"           : country prefix (2 chars).
 * - DDDD           : 4-digit distribuidora code.
 * - AAAAAAAAAAAA   : 12-digit supply-point identifier (free assignment by distribuidora).
 * - CC             : 2-letter control code, mod-23 checksum over the 16
 *                    preceding digits using the alphabet
 *                    "TRWAGMYFPDXBNJZSQVHLCKE" (23 letters, no I / O / U).
 * - FF (optional)  : 2-char border-point suffix for additional meters at the
 *                    same supply point — formatted as digit + letter.
 *
 * The validator is checksum-aware: codes that match the structure but carry
 * an incorrect control pair are rejected.
 */
object CupsValidation {
    private const val CONTROL_ALPHABET = "TRWAGMYFPDXBNJZSQVHLCKE"

    private val STRUCTURE_REGEX =
        Regex("^ES(\\d{16})([A-Z]{2})([0-9][A-Z])?$")

    /**
     * Normalizes a raw CUPS by uppercasing and stripping any non-alphanumeric
     * character (spaces, hyphens, dots, …). Idempotent.
     */
    fun normalize(raw: String): String =
        raw.uppercase().filter { it.isLetterOrDigit() }

    fun isValid(raw: String): Boolean {
        val normalized = normalize(raw)
        val match = STRUCTURE_REGEX.matchEntire(normalized) ?: return false
        val supplyDigits = match.groupValues[1]
        val controlCode = match.groupValues[2]
        return computeControlCode(supplyDigits) == controlCode
    }

    /**
     * Computes the 2-letter control code for the 16-digit supply identifier
     * (positions 3..18 of a CUPS, i.e. distribuidora + punto de suministro).
     *
     * Returns `null` if the input is not exactly 16 digits.
     */
    fun computeControlCode(sixteenDigits: String): String? {
        if (sixteenDigits.length != 16 || !sixteenDigits.all { it.isDigit() }) return null
        val n = sixteenDigits.toLong()
        val mod = (n % 529L).toInt()
        val first = mod / 23
        val second = mod % 23
        return "${CONTROL_ALPHABET[first]}${CONTROL_ALPHABET[second]}"
    }
}
