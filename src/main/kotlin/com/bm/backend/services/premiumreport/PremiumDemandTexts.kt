package com.bm.backend.services.premiumreport

import com.bm.backend.models.TariffPeriodProfile

/**
 * Spanish copy of the demanded-power page (spec report-demand-page truth table, approved by the user 2026-10-29).
 * Phrases with the same meaning as the active/reactive pages reference [PremiumActiveTexts]/[PremiumReactiveTexts].
 * Descriptive only: the comparison with contracted power is plain arithmetic, with no verdict, threshold or advice.
 */
object PremiumDemandTexts {
    // H0 / W1 / W2.
    const val SECTION = "POTENCIA DEMANDADA"
    const val WINDOW_LINE = PremiumReactiveTexts.WINDOW_LINE
    const val LATEST_WINDOW = PremiumReactiveTexts.LATEST_WINDOW
    const val NO_DATA = PremiumReactiveTexts.NO_DATA
    fun windowValue(range: String) = PremiumReactiveTexts.windowValue(range)

    // H1 / C1–C3 / M1 / M2 / S1.
    const val STATS_TITLE = "Potencia máxima registrada por periodo (últimos 12 meses)"
    const val COL_PERIOD = PremiumActiveTexts.COL_PERIOD
    const val COL_READINGS = "Lecturas"
    const val COL_PEAK = "Máxima registrada (kW)"
    const val COL_PEAK_READING = "Lectura de la máxima"
    const val COL_MEAN = "Media de máximas (kW)"
    const val MISSING = PremiumActiveTexts.MISSING
    const val MEAN_WINDOW_INCOMPLETE = "No calculada: ventana parcial"
    const val MEAN_OVERLAP = "No calculada: lecturas solapadas"
    const val STATS_CAPTION =
        "Máxima registrada: el mayor valor de maxímetro de las lecturas de la ventana, con las fechas de esa lectura. " +
            "Media de máximas: media de los máximos registrados en cada lectura, incluidos los ceros; no es una potencia media."

    // N1–N10.
    const val NO_DEMAND_ROWS = "El histórico no contiene lecturas de potencia demandada."
    const val DEMAND_UNAVAILABLE = "Datos de potencia demandada no disponibles."
    const val NOTHING_IN_WINDOW =
        "Ninguna lectura de potencia demandada cae por completo dentro de los últimos 12 meses; no se muestran cifras para esta ventana."
    fun partialWindow(covered: Long, windowDays: Long) =
        "Ventana parcial: las lecturas cubren $covered de $windowDays días; las máximas se refieren solo a ese periodo."
    fun crossing(n: Int) =
        if (n == 1) "1 lectura cruza el límite de la ventana y no se tiene en cuenta."
        else "$n lecturas cruzan el límite de la ventana y no se tienen en cuenta."
    const val RECORDED_ZERO =
        "Potencia demandada registrada: 0 kW en todas las lecturas con valor (dato registrado, no ausencia de datos)."
    const val NULL_CELLS = PremiumReactiveTexts.NULL_CELLS
    fun noReadings(periods: List<Int>) = "Sin lecturas en la fuente para ${periods.joinToString(", ") { "P$it" }}."
    const val OVERLAP_TAIL = "ambas se tienen en cuenta para la máxima; la media de máximas no se calcula."
    fun overlapNote(range: String) = "${PremiumActiveTexts.OVERLAPPED_READINGS} $range: $OVERLAP_TAIL"
    const val ACTIVE_ZERO_NOTE =
        "Hay lecturas sin consumo de energía activa con potencia demandada registrada; se muestran y se tienen en cuenta (°)."

    // K0–K8 / KU1 / KU2.
    const val COMPARISON_TITLE = "Máxima registrada y potencia contratada"
    const val COMPARISON_NO_CONTRACTED = "Comparación no disponible: la potencia contratada no está disponible."
    const val COMPARISON_NO_READINGS =
        "Comparación no disponible: no hay lecturas de potencia demandada dentro de los últimos 12 meses."
    const val COMPARISON_BASIS =
        "Potencia contratada según la información general del suministro. Diferencia = máxima registrada − potencia contratada. " +
            "% = máxima registrada ÷ potencia contratada × 100."
    const val UNIT = "kW"
    const val COMPARISON_CAPTION = "Barra: máxima registrada (kW). Línea vertical: potencia contratada (kW)."
    const val COMPARISON_PARTIAL = "La máxima registrada corresponde a una ventana parcial."
    const val COL_CONTRACTED = "Potencia contratada (kW)"
    const val COL_DIFF = "Diferencia (kW)"
    const val COL_PCT = "Máxima / contratada (%)"
    const val NO_CONTRACTED = "Sin potencia contratada"
    const val CONTRACTED_ZERO = "No calculado: potencia contratada 0"
    const val NO_READINGS_CELL = "No calculado: sin lecturas"

    // G0–G4 / T0–T3.
    const val READINGS_CHART_TITLE = "Máxima registrada por lectura (últimos 12 meses)"
    val READINGS_CAPTION = readingsCaption(TariffPeriodProfile.ALL_PERIODS)

    /** T4a contiguous (`entre P1 y P2`), T4b single (`en P2`), T4c non-contiguous (`en P1, P3`) power periods. */
    fun readingsCaption(periods: List<Int>): String {
        val contiguous = periods.size >= 2 && periods.zipWithNext().all { (a, b) -> b == a + 1 }
        val where = when {
            periods.size == 1 -> "en P${periods[0]}"
            contiguous -> "entre P${periods.first()} y P${periods.last()}"
            else -> "en " + periods.joinToString(", ") { "P$it" }
        }
        return "Cada barra es una lectura de la fuente (no un mes): la altura es el mayor valor de maxímetro registrado $where " +
            "en esa lectura (kW) y la anchura los días que cubre. Contorno sin relleno: lectura solapada. Raya sobre el eje: lectura con valor 0."
    }
    fun readingsCap(shown: Int, total: Int) = PremiumActiveTexts.readingsCap(shown, total)
    const val READINGS_CHART_UNAVAILABLE =
        "Gráfico de lecturas no disponible: no hay lecturas de potencia demandada dentro de los últimos 12 meses."
    const val READINGS_TABLE_TITLE = "Lecturas de potencia demandada (últimos 12 meses)"
    const val COL_FROM = PremiumActiveTexts.COL_FROM
    const val COL_TO = PremiumActiveTexts.COL_TO
    const val COL_DAYS = PremiumActiveTexts.COL_DAYS
    const val UNIT_SUFFIX = " (kW)"
    const val DATES_NOTE = "Fechas inclusivas; valores en kW tal como los publica la fuente."
    const val OVERLAP_MARK = "†"
    const val ACTIVE_ZERO_MARK = "°"
    const val OVERLAP_FOOTNOTE = "† Lectura solapada; se tiene en cuenta."
    const val ACTIVE_ZERO_FOOTNOTE = "° Lectura sin consumo de energía activa; su potencia demandada se muestra."
}
