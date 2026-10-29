package com.bm.backend.models

/**
 * Tariff periods the Premium report renders. [energyPeriods] drive active/reactive energy, [powerPeriods] power and
 * maximeter demand (period numbers 1–6, ascending). [tariffCode] is the normalised code (e.g. `2.0TD`) when one was
 * identified; [snapshotTariff] / [tariffName] are the source texts as published (trimmed), kept for disclosures.
 */
data class TariffPeriodProfile(
    val tariffCode: String?,
    val energyPeriods: List<Int>,
    val powerPeriods: List<Int>,
    val status: TariffProfileStatus,
    val snapshotTariff: String? = null,
    val tariffName: String? = null
) {
    companion object {
        val ALL_PERIODS: List<Int> = (1..6).toList()
    }
}
