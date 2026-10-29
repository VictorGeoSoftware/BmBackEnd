package com.bm.backend.services.premiumreport

/** Spanish copy of the active-energy page (spec report-active-page D3–D9, approved by the user 2026-10-29). */
object PremiumActiveTexts {
    const val SECTION = "ENERGÍA ACTIVA"

    const val HISTORY_AVAILABLE = "Histórico disponible:"
    const val NO_DATA = "Sin datos"
    const val PARTIAL_HISTORY = "Histórico inferior a 12 meses completos: las cifras se muestran como parciales."

    // Block headings.
    const val WINDOWS_TITLE = "Consumo de energía activa por ventanas de 12 meses"
    const val DISTRIBUTION_TITLE = "Distribución por periodo (últimos 12 meses)"
    const val READINGS_CHART_TITLE = "Consumo medio diario por lectura"
    const val COMPARISON_TITLE = "Comparación con fuentes anuales"
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

    // D6 readings chart.
    const val READINGS_CAPTION =
        "Cada barra es una lectura de la fuente (no un mes): la altura es el consumo medio diario (kWh/día) " +
            "y la anchura los días que cubre. Contorno sin relleno: lectura solapada. Banda rayada: lectura sin valores. " +
            "Raya sobre el eje: lectura con consumo 0."
    const val READINGS_CHART_UNAVAILABLE = "Gráfico de lecturas no disponible: no hay lecturas con fechas válidas."
    fun readingsCap(shown: Int, total: Int) = "Se muestran las $shown lecturas más recientes de $total."

    // D7 high-consumption intervals.
    const val HIGH_TITLE = "Intervalos de mayor consumo medio diario"
    const val HIGH_CAPTION = "Calculado sobre"
    const val HIGH_EMPTY = "No hay intervalos disponibles para clasificar."
    const val COL_FROM = "Desde"
    const val COL_TO = "Hasta"
    const val COL_DAYS = "Días"
    const val COL_KWH = "kWh"
    const val COL_KWH_DAY = "kWh/día"
    const val COL_DOMINANT = "Periodo dominante"

    // D8 source comparison.
    const val SOURCE_SIPS = "SIPS (distribuidora)"
    const val SOURCE_LEGACY = "Consumo anual del estudio básico"
    const val COL_SOURCE = "Fuente"
    const val COL_HISTORY = "Histórico"
    const val COL_DIFF_KWH = "Diferencia kWh"
    const val COL_DIFF_PCT = "Diferencia %"
    const val TOTAL = "Total"
    fun comparisonNote(window: String) = "El histórico cubre $window; la fuente no indica su periodo de referencia."
    const val NOT_COMPARABLE = "Comparación no posible: la ventana de 12 meses no está completa"
    const val NOT_COMPARABLE_OVERLAP = "Comparación no posible: los últimos 12 meses tienen lecturas solapadas sin resolver."
    const val NOT_COMPARABLE_NO_HISTORY = "Comparación no posible: no hay histórico de energía activa."
    const val SOURCE_UNAVAILABLE = "Fuente no disponible"

    // D9 readings table.
    const val CROSSING_NOTE = "* Lectura que cruza el límite de la ventana; no se suma."
    const val DATES_NOTE = "Fechas inclusivas; valores en kWh tal como los publica la fuente."
}
