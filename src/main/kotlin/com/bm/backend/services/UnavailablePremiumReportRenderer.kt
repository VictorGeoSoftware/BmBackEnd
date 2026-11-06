package com.bm.backend.services

import com.bm.backend.models.ReportDataset

/** Production default until `report-document` plugs in the real PDF. */
class UnavailablePremiumReportRenderer : PremiumReportRenderer {
    override fun render(dataset: ReportDataset): ByteArray = throw PremiumReportUnavailableException()
}
