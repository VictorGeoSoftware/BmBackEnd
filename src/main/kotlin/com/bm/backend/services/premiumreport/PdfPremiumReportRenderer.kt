package com.bm.backend.services.premiumreport

import com.bm.backend.models.ReportDataset
import com.bm.backend.services.PremiumReportRenderer
import com.bm.backend.utils.DemandSummaryCalculator
import com.bm.backend.utils.HistoryCoverageCalculator
import com.bm.backend.utils.ReactiveSummaryCalculator
import java.time.Clock

/**
 * Assembles the accepted Premium sections into one PDF (spec report-document): Cover → Active → Reactive →
 * Demand → Comparison → Company (Q1 a), no contents page (Q2 a), F1 footer (Q3 a). Reactive/demand are omitted
 * only when the history holds no readings of that family (N1, Q4 b); any other unavailable reason keeps the
 * section with its approved disclosure. Stateless: sections and the footer are created per render.
 * [sections] overrides the dataset-driven list (tests only).
 */
class PdfPremiumReportRenderer(
    private val clock: Clock,
    private val sections: ((ReportDataset) -> List<PremiumReportSection>)? = null
) : PremiumReportRenderer {

    override fun render(dataset: ReportDataset): ByteArray {
        val list = sections?.invoke(dataset) ?: defaultSections(dataset)
        return PremiumReportPdf.render(list, dataset, PremiumPageFooter())
    }

    fun defaultSections(dataset: ReportDataset): List<PremiumReportSection> {
        val coverage = HistoryCoverageCalculator.calculate(dataset.history)
        val reactive = ReactiveSummaryCalculator.calculate(dataset.history, coverage)
        val demand = DemandSummaryCalculator.calculate(dataset.history, coverage, dataset.snapshot)
        return listOfNotNull(
            PremiumCoverSection(clock),
            PremiumActiveSection(),
            PremiumReactiveSection().takeUnless { reactive.reason == ReactiveSummaryCalculator.NO_REACTIVE_ROWS },
            PremiumDemandSection().takeUnless { demand.reason == DemandSummaryCalculator.NO_DEMAND_ROWS },
            PremiumComparisonSection(),
            PremiumCompanySection()
        )
    }
}
