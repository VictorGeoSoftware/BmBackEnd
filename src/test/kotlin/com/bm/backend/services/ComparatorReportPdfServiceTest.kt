package com.bm.backend.services

import com.bm.backend.models.ReportDatasetRequest
import com.bm.backend.testing.N8nFixtures
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

/** Smoke: the Basic comparator PDF still renders the same client-built request the dataset reuses. */
class ComparatorReportPdfServiceTest {
    @Test
    fun `basic comparator pdf renders the synthetic comparison`() {
        val text = requireNotNull(javaClass.getResource("/report/comparison_request.json")).readText()
        val request = N8nFixtures.json.decodeFromString(ReportDatasetRequest.serializer(), text)
        val bytes = ComparatorReportPdfService().generate(request.comparison)
        assertTrue(bytes.size > 1000)
        assertTrue(String(bytes.copyOfRange(0, 5)) == "%PDF-")
    }
}
