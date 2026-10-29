package com.bm.backend.services.premiumreport

import com.bm.backend.models.PeriodValues
import com.bm.backend.models.TariffPeriodProfile
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

    /**
     * `P1: 15,1 kW · P2: 20 kW` (non-breaking inside each entry) for reported periods of the tariff's [periods] only;
     * null when none reported.
     */
    fun periodPowers(values: PeriodValues, periods: List<Int> = TariffPeriodProfile.ALL_PERIODS): String? =
        listOf(values.p1, values.p2, values.p3, values.p4, values.p5, values.p6)
            .mapIndexedNotNull { i, v -> v?.takeIf { (i + 1) in periods }?.let { "P${i + 1}:$NBSP${number(it)}${NBSP}kW" } }
            .takeIf { it.isNotEmpty() }
            ?.joinToString(" · ")

    /** Source ISO date (optionally with time) as `dd/MM/yyyy`, without time-zone shifting. */
    fun isoDate(value: String): String = LocalDate.parse(value.take(10)).format(DATE)

    fun issueDate(clock: Clock): String = LocalDate.now(clock.withZone(ZONE)).format(DATE)

    fun uppercase(value: String): String = value.uppercase(LOCALE)

    /** Integer kWh with Spanish grouping (`72677.0` → `72.677`, `-100.0` → `-100`). */
    fun kwh(value: Double): String {
        val symbols = DecimalFormatSymbols(LOCALE).apply { minusSign = '-' }
        val format = DecimalFormat("#,##0", symbols)
        format.roundingMode = RoundingMode.HALF_UP
        val text = format.format(value)
        return if (text == "-0") "0" else text
    }

    /** Signed integer kWh difference (`+201`, `-100`, `0`). */
    fun signedKwh(value: Double): String = kwh(value).let { if (it != "0" && !it.startsWith("-")) "+$it" else it }

    /** Exactly one decimal with Spanish grouping (`208.75` → `208,8`, `3027.5` → `3.027,5`); used for kWh/día. */
    fun decimal1(value: Double): String {
        val symbols = DecimalFormatSymbols(LOCALE).apply { minusSign = '-' }
        val format = DecimalFormat("#,##0.0", symbols)
        format.roundingMode = RoundingMode.HALF_UP
        return format.format(value).let { if (it == "-0,0") "0,0" else it }
    }

    /** Exactly two decimals with Spanish grouping, HALF_UP (`0.125` → `0,13`, `-0.001` → `0,00`); used for kVArh/kWh. */
    fun decimal2(value: Double): String {
        val symbols = DecimalFormatSymbols(LOCALE).apply { minusSign = '-' }
        val format = DecimalFormat("#,##0.00", symbols)
        format.roundingMode = RoundingMode.HALF_UP
        return format.format(value).let { if (it == "-0,00") "0,00" else it }
    }

    /** One-decimal percentage (`9.908` → `9,9 %`). */
    fun percent(value: Double): String {
        val symbols = DecimalFormatSymbols(LOCALE).apply { minusSign = '-' }
        val format = DecimalFormat("#,##0.0", symbols)
        format.roundingMode = RoundingMode.HALF_UP
        val text = format.format(value)
        return "${if (text == "-0,0") "0,0" else text} %"
    }

    /** Signed one-decimal percentage (`+11,9 %`, `-3,0 %`). */
    fun signedPercent(value: Double): String = percent(value).let { if (!it.startsWith("-") && !it.startsWith("0,0 ")) "+$it" else it }

    /** Signed kW difference with published precision up to 3 decimals (`+0,903`, `-2,5`, `0`; never `-0`). */
    fun signedNumber(value: Double): String {
        val symbols = DecimalFormatSymbols(LOCALE).apply { minusSign = '-' }
        val format = DecimalFormat("#,##0.###", symbols)
        format.roundingMode = RoundingMode.HALF_UP
        val text = format.format(value).let { if (it == "-0") "0" else it }
        return if (text != "0" && !text.startsWith("-")) "+$text" else text
    }

    fun date(value: LocalDate): String = value.format(DATE)

    /** Half-open `[start, endExclusive)` shown inclusively: `dd/MM/yyyy – dd/MM/yyyy` (end − 1 day). */
    fun inclusiveRange(start: LocalDate, endExclusive: LocalDate, separator: String = " – "): String =
        "${date(start)}$separator${date(endExclusive.minusDays(1))}"
}
