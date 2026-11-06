package com.bm.backend.services.premiumreport

import com.bm.backend.models.ReportDataset
import com.lowagie.text.Document
import com.lowagie.text.pdf.PdfWriter

/**
 * One section of the Premium report. A section sets its own page size and then calls `document.newPage()`
 * (a no-op on a still-empty page) before writing, so sections can mix portrait and landscape pages.
 */
fun interface PremiumReportSection {
    fun write(document: Document, writer: PdfWriter, dataset: ReportDataset)
}
