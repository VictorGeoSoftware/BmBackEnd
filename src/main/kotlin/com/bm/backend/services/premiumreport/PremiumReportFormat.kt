package com.bm.backend.services.premiumreport

import com.bm.backend.models.PeriodValues
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Spanish presentation formats shared by Premium report pages. Values are shown as published, never invented. */
object PremiumReportFormat {
    val LOCALE: Locale = Locale.forLanguageTag("es-ES")
    val ZONE: ZoneId = ZoneId.of("Europe/Madrid")
    private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")

    /** Comma decimal, `.` thousands, published precision up to 3 decimals (`15.1` → `15,1`, `34.0` → `34`). */
    fun number(value: Double): String {
        val format = DecimalFormat("#,##0.###", DecimalFormatSymbols(LOCALE))
        format.roundingMode = RoundingMode.HALF_UP
        return format.format(value)
    }

    /** Non-breaking space: keeps "P1: 15,1 kW" on one line when the list wraps. */
    const val NBSP = '\u00A0'

    /** `P1: 15,1 kW · P2: 20 kW` (non-breaking inside each entry) for reported periods only; null when none reported. */
    fun periodPowers(values: PeriodValues): String? =
        listOf(values.p1, values.p2, values.p3, values.p4, values.p5, values.p6)
            .mapIndexedNotNull { i, v -> v?.let { "P${i + 1}:$NBSP${number(it)}${NBSP}kW" } }
            .takeIf { it.isNotEmpty() }
            ?.joinToString(" · ")

    /** Source ISO date (optionally with time) as `dd/MM/yyyy`, without time-zone shifting. */
    fun isoDate(value: String): String = LocalDate.parse(value.take(10)).format(DATE)

    fun issueDate(clock: Clock): String = LocalDate.now(clock.withZone(ZONE)).format(DATE)

    fun uppercase(value: String): String = value.uppercase(LOCALE)
}
