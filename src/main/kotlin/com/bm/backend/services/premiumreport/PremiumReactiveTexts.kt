package com.bm.backend.services.premiumreport

/**
 * Spanish copy of the reactive-energy page (spec report-reactive-page truth table, approved by the user 2026-10-29).
 * Phrases with the same meaning as the active page reference [PremiumActiveTexts] instead of duplicating them.
 * Descriptive only: no penalties, power factor, thresholds, verdicts or advice (X1 omitted, Q3 (b)).
 */
object PremiumReactiveTexts {
    // H0 / W1 / W2.
    const val SECTION = "ENERGÍA REACTIVA"
    const val WINDOW_LINE = "Ventana analizada:"
    const val LATEST_WINDOW = PremiumActiveTexts.LATEST_WINDOW
    const val NO_DATA = PremiumActiveTexts.NO_DATA
    fun windowValue(range: String) = "$LATEST_WINDOW ($range)"

    // H1 / C1–C7.
    const val WINDOW_TITLE = "Energía reactiva de los últimos 12 meses"
    const val COL_PERIOD = PremiumActiveTexts.COL_PERIOD
    const val COL_STATUS = PremiumActiveTexts.COL_STATUS
    const val COL_COVERED_DAYS = PremiumActiveTexts.COL_COVERED_DAYS
    const val UNIT_SUFFIX = " (kVArh)"
    const val COL_TOTAL_KVARH = "Total (kVArh)"
    const val STATUS_COMPLETE = PremiumActiveTexts.STATUS_COMPLETE
    const val STATUS_INCOMPLETE = PremiumActiveTexts.STATUS_INCOMPLETE
    const val STATUS_OVERLAP = PremiumActiveTexts.STATUS_OVERLAP
    const val STATUS_UNAVAILABLE = PremiumActiveTexts.STATUS_UNAVAILABLE
    const val PARTIAL_PREFIX = PremiumActiveTexts.PARTIAL_PREFIX
    const val OVERLAP_NO_FIGURE = PremiumActiveTexts.OVERLAP_NO_FIGURE
    const val MISSING = PremiumActiveTexts.MISSING

    // N1–N9.
    const val NO_REACTIVE_ROWS = "El histórico no contiene lecturas de energía reactiva."
    const val REACTIVE_UNAVAILABLE = "Datos de energía reactiva no disponibles."
    const val NOTHING_IN_WINDOW =
        "Ninguna lectura de energía reactiva cae por completo dentro de los últimos 12 meses; no se muestra cifra para esta ventana."
    fun partialWindow(covered: Long, windowDays: Long) =
        "Ventana parcial: las lecturas cubren $covered de $windowDays días; las cifras no son un total de 12 meses."
    const val CROSSING_ONE = "1 lectura cruza el límite de la ventana y no se suma."
    fun crossing(n: Int) = if (n == 1) CROSSING_ONE else "$n lecturas cruzan el límite de la ventana y no se suman."
    const val RECORDED_ZERO =
        "Energía reactiva registrada: 0 kVArh en todas las lecturas con valor (dato registrado, no ausencia de datos)."
    const val NULL_CELLS = "Las celdas sin valor en la fuente se muestran como «—» y no se cuentan como 0."
    fun overlapNote(range: String, zero: Boolean) =
        "${PremiumActiveTexts.OVERLAPPED_READINGS} $range: " +
            if (zero) PremiumActiveTexts.OVERLAP_ZERO_NOTE else PremiumActiveTexts.OVERLAP_BLOCKING_NOTE

    // D0–D4.
    const val DISTRIBUTION_TITLE = PremiumActiveTexts.DISTRIBUTION_TITLE
    const val UNIT = "kVArh"
    const val DISTRIBUTION_NOT_COMPLETE = "${PremiumActiveTexts.DISTRIBUTION_UNAVAILABLE} ${PremiumActiveTexts.DISTRIBUTION_NOT_COMPLETE}"
    const val DISTRIBUTION_OVERLAP = "${PremiumActiveTexts.DISTRIBUTION_UNAVAILABLE} ${PremiumActiveTexts.DISTRIBUTION_OVERLAP}"
    const val DISTRIBUTION_ZERO = "${PremiumActiveTexts.DISTRIBUTION_UNAVAILABLE} la energía reactiva registrada es 0."

    // Q0 / B1 / B2 / R1–R3 / U0–U5.
    const val RATIO_TITLE = "Energía reactiva por kWh activo"
    fun basis(range: String) =
        "Cociente descriptivo: kVArh registrados por cada kWh de energía activa del mismo periodo ($range), " +
            "según el histórico de energía activa."
    const val COL_REACTIVE = "Reactiva (kVArh)"
    const val COL_ACTIVE = "Activa (kWh)"
    const val COL_RATIO = "kVArh/kWh"
    const val TOTAL = PremiumActiveTexts.TOTAL
    const val RATIO_PERIOD_MISSING = "Sin dato en la fuente"
    const val RATIO_NULL_CELLS = "No calculado: hay celdas sin valor"
    const val RATIO_ACTIVE_ZERO = "No calculado: energía activa 0"
    const val RATIO_UNAVAILABLE = "Cociente kVArh/kWh no disponible:"
    const val RATIO_REACTIVE_OVERLAP = "la energía reactiva de los últimos 12 meses tiene lecturas solapadas sin resolver."
    const val RATIO_REACTIVE_INCOMPLETE = "la energía reactiva no cubre por completo los últimos 12 meses."
    const val RATIO_ACTIVE_UNAVAILABLE = "no hay lecturas de energía activa dentro de los últimos 12 meses."
    const val RATIO_ACTIVE_OVERLAP = "la energía activa de los últimos 12 meses tiene lecturas solapadas sin resolver."
    const val RATIO_ACTIVE_INCOMPLETE = "la energía activa no cubre por completo los últimos 12 meses."

    // G0–G4 / T0–T2.
    const val READINGS_CHART_TITLE = "Energía reactiva media diaria por lectura (últimos 12 meses)"
    const val UNIT_PER_DAY = "kVArh/día"
    const val READINGS_CAPTION =
        "Cada barra es una lectura de la fuente (no un mes): la altura es la energía reactiva media diaria (kVArh/día) " +
            "y la anchura los días que cubre. Contorno sin relleno: lectura solapada sin resolver. Raya sobre el eje: lectura con valor 0."
    fun readingsCap(shown: Int, total: Int) = PremiumActiveTexts.readingsCap(shown, total)
    const val READINGS_CHART_UNAVAILABLE =
        "Gráfico de lecturas no disponible: no hay lecturas de energía reactiva dentro de los últimos 12 meses."
    const val READINGS_TABLE_TITLE = "Lecturas de energía reactiva (últimos 12 meses)"
    const val COL_FROM = PremiumActiveTexts.COL_FROM
    const val COL_TO = PremiumActiveTexts.COL_TO
    const val COL_DAYS = PremiumActiveTexts.COL_DAYS
    const val DATES_NOTE = "Fechas inclusivas; valores en kVArh tal como los publica la fuente."
    const val OVERLAP_MARK = "†"
    const val OVERLAP_FOOTNOTE = "† Lectura solapada sin resolver."
}
