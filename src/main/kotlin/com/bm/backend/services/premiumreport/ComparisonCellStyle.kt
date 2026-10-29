package com.bm.backend.services.premiumreport

/** Visual role of a comparison-table cell; mirrors the Basic comparator's cell kinds (font and background). */
enum class ComparisonCellStyle {
    /** Yellow header (tariff, `DATOS DISTRIBUIDORA`). */
    HEADER_HIGHLIGHT,
    /** Blue header of the current-conditions column. */
    HEADER_CURRENT,
    /** White header of an offer column. */
    HEADER_OFFER,
    /** Bold side title spanning the power rows. */
    SIDE_TITLE,
    /** Bold energy side title followed by the highlighted total kWh after the blank line. */
    SIDE_TITLE_ENERGY,
    /** Regular value cell. */
    VALUE,
    /** Bold label of a meta row (extra services, taxes). */
    META_LABEL,
    /** Yellow bold total row (label or value). */
    TOTAL,
    /** Blank, borderless filler before `AHORRO ANUAL`. */
    BLANK,
    /** Green savings cell (label, positive, zero or empty delta). */
    SAVINGS,
    /** Red savings cell (delta starting with `-`). */
    SAVINGS_NEGATIVE
}
