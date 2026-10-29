package com.bm.backend.services.premiumreport

/** Approved document-level copy for the assembled Premium report (spec report-document, F1 approved 2026-10-29). */
object PremiumDocumentTexts {
    /** F1 prefix: the full footer reads `Página {n} de {total}`. */
    fun pagePrefix(page: Int): String = "Página $page de "
}
