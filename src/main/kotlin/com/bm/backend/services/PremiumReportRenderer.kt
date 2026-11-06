package com.bm.backend.services

import com.bm.backend.models.ReportDataset

/** Renders the comprehensive Premium PDF from an assembled, authorised dataset. Bound in `Application.kt`. */
fun interface PremiumReportRenderer {
    fun render(dataset: ReportDataset): ByteArray
}
