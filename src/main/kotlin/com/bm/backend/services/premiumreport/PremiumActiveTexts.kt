package com.bm.backend.services.premiumreport

/** Spanish copy of the active-energy page (spec report-active-page D3–D9, approved by the user 2026-10-29; daily-average chart, high intervals and source
 * comparisons removed and a per-reading chart added by feedback 2026-11-03). */
object PremiumActiveTexts {
    const val SECTION = "ENERGÍA ACTIVA"

    const val HISTORY_AVAILABLE = "Histórico disponible:"
    const val NO_DATA = "Sin datos"
    const val PARTIAL_HISTORY = "Histórico inferior a 12 meses completos: las cifras se muestran como parciales."

    // Block headings.
    const val WINDOWS_TITLE = "Consumo de energía activa por ventanas de 12 meses"
    const val DISTRIBUTION_TITLE = "Distribución por periodo (últimos 12 meses)"
    const val READINGS_CHART_TITLE = "Consumo de energía activa por lectura"
    const val READINGS_TABLE_TITLE = "Lecturas de energía activa"

    // D4 window table.
    const val COL_PERIOD = "Periodo"
    const val COL_STATUS = "Estado"
    const val COL_COVERED_DAYS = "Días cubiertos"
    const val COL_TOTAL_KWH = "Total (kWh)"
    const val LATEST_WINDOW = "Últimos 12 meses"
    const val PREVIOUS_WINDOW = "12 meses anteriores"
    const val STATUS_COMPLETE = "Completo"
    const val STATUS_INCOMPLETE = "Parcial"
    const val STATUS_OVERLAP = "Sin cifra firme"
    const val STATUS_UNAVAILABLE = "Sin datos"
    const val PARTIAL_PREFIX = "Parcial:"
    const val OVERLAP_NO_FIGURE = "Sin cifra firme: lecturas solapadas sin resolver"
    const val MISSING = "—"
    const val YOY = "Variación interanual:"
    const val YOY_UNAVAILABLE = "Variación interanual no disponible"
    const val YOY_LATEST_INCOMPLETE = "los últimos 12 meses no están completos"
    const val YOY_PREVIOUS_INCOMPLETE = "los 12 meses anteriores no están completos"
    const val YOY_PREVIOUS_ZERO = "el consumo de los 12 meses anteriores es 0"
    const val YOY_NO_DATA = "sin datos"
    const val YOY_LATEST_OVERLAP = "los últimos 12 meses tienen lecturas solapadas sin resolver"
    const val YOY_PREVIOUS_OVERLAP = "los 12 meses anteriores tienen lecturas solapadas sin resolver"
    const val OVERLAP_ZERO_NOTE = "una de ellas sin consumo; no altera el total."
    const val OVERLAP_BLOCKING_NOTE = "sin resolver; no se muestra cifra firme."
    const val OVERLAPPED_READINGS = "Lecturas solapadas"

    // D5 distribution.
    const val DISTRIBUTION_UNAVAILABLE = "Distribución por periodo no disponible:"
    const val DISTRIBUTION_NOT_COMPLETE = "no hay 12 meses completos."
    const val DISTRIBUTION_OVERLAP = "lecturas solapadas sin resolver."
    const val DISTRIBUTION_ZERO = "el consumo total es 0."

    // Per-reading chart (feedback 2026-11-03).
    const val UNIT_KWH = "kWh"
    val READINGS_CAPTION: String get() = PremiumReportTexts.stackedReadingsCaption(UNIT_KWH)

    // D9 readings table.
    const val COL_FROM = "Desde"
    const val COL_TO = "Hasta"
    const val COL_DAYS = "Días"
    const val TOTAL = "Total"
    const val CROSSING_NOTE = "* Lectura que cruza el límite de la ventana; no se suma."
    const val DATES_NOTE = "Fechas inclusivas; valores en kWh tal como los publica la fuente."
}
