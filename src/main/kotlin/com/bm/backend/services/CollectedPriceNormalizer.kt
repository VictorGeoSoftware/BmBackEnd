package com.bm.backend.services

/**
 * Canonical forms for the two free-text fields of a collected-prices submission.
 *
 * Both the app and this service must agree on the tariff canonical form, otherwise
 * the 2.0TD exclusion could be applied inconsistently on the two sides.
 */
object CollectedPriceNormalizer {

    /** Canonical form of the tariff excluded from collection. */
    const val EXCLUDED_TARIFF_CANONICAL = "20TD"

    /**
     * Canonical tariff form used for comparisons: uppercase, with every non
     * alphanumeric character removed.
     *
     * This collapses the variants seen in bill reads — "2.0TD", "2.0 TD", "2.0Td " —
     * onto a single value, so the 2.0TD exclusion cannot be defeated by whitespace or
     * punctuation drift from the PDF/n8n extraction.
     */
    fun canonicalTariff(raw: String): String =
        raw.uppercase().filter { character -> character.isLetterOrDigit() }

    /**
     * Storage form of the tariff: trimmed and uppercased, but otherwise as reported,
     * so the dashboard still shows "3.0TD" rather than "30TD".
     */
    fun displayTariff(raw: String): String = raw.trim().uppercase()

    /**
     * Canonical supplier form used for grouping: trimmed, lowercased, with internal
     * whitespace collapsed to a single space.
     *
     * Deliberately conservative — it does not strip legal suffixes such as "S.A." or
     * "S.L.U.", because doing so would merge genuinely distinct entities. It only
     * removes the differences that are certainly noise.
     */
    fun normalizeCompanyName(raw: String): String =
        raw.trim().lowercase().replace(WHITESPACE_RUN, " ")

    /**
     * Storage form of the supplier: trimmed, with internal whitespace collapsed, but
     * casing preserved exactly as the broker typed it.
     */
    fun displayCompanyName(raw: String): String =
        raw.trim().replace(WHITESPACE_RUN, " ")

    private val WHITESPACE_RUN = Regex("\\s+")
}
