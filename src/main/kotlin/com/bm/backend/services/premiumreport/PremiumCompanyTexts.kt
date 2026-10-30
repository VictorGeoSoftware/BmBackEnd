package com.bm.backend.services.premiumreport

import com.bm.backend.services.ComparatorReportPdfTexts

/**
 * Approved copy of the closing company page (user decisions 2026-10-29: Q1 c, Q2 a/i, Q3 b, Q4 b, Q5 a).
 * The data-protection text is referenced from the Basic constant (single source), never copied.
 */
object PremiumCompanyTexts {
    const val DATA_PROTECTION_SECTION = "INFORMACIÓN SOBRE PROTECCIÓN DE DATOS"
    const val COMPANY_NAME = "BRIEL MARNYSOS CONSULTORÍA ENERGÉTICA S.L.U."

    val DATA_PROTECTION: String get() = ComparatorReportPdfTexts.DATA_PROTECTION_DISCLAIMER

    /** Every rendered string, in page order. */
    val ORDERED: List<String> get() = listOf(DATA_PROTECTION_SECTION, COMPANY_NAME, DATA_PROTECTION)
}
