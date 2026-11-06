package com.bm.backend.services.premiumreport

import com.bm.backend.models.ComparatorReportProposal

/** One landscape page of the comparison: the offers it shows (request order) and its cells in reading order. */
data class ComparisonPage(
    val offers: List<ComparatorReportProposal>,
    val columnCount: Int,
    val cells: List<ComparisonCell>
)
