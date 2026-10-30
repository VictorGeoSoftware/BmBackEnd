package com.bm.backend.services

import com.bm.backend.models.GrantedFirebaseUser
import com.bm.backend.models.PremiumReportResult
import com.bm.backend.models.ReportDatasetRequest
import com.bm.backend.models.ReportDatasetResult
import com.bm.backend.models.UserCapability

/**
 * Orchestrates a Premium report: capability from the caller's current tier, owner-scoped dataset, then exactly one
 * render. The renderer is never invoked for a forbidden or rejected request.
 */
class PremiumReportService(
    private val reportDatasetService: ReportDatasetService,
    private val renderer: PremiumReportRenderer
) {
    fun isAllowed(user: GrantedFirebaseUser): Boolean =
        UserCapability.COMPREHENSIVE_COMPARATOR_PDF in user.capabilities

    fun generate(request: ReportDatasetRequest, user: GrantedFirebaseUser): PremiumReportResult {
        if (!isAllowed(user)) return PremiumReportResult.Forbidden
        val dataset = when (val assembled = reportDatasetService.assemble(request, user.uid)) {
            is ReportDatasetResult.Rejected -> return PremiumReportResult.Rejected(assembled.reason)
            is ReportDatasetResult.Assembled -> assembled.dataset
        }
        return try {
            PremiumReportResult.Generated(renderer.render(dataset))
        } catch (e: PremiumReportUnavailableException) {
            PremiumReportResult.Unavailable
        } catch (e: Exception) {
            PremiumReportResult.Failed(e.javaClass.simpleName)
        }
    }
}
