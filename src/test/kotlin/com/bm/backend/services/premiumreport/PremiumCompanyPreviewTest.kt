package com.bm.backend.services.premiumreport

import com.bm.backend.models.StudySnapshot
import com.bm.backend.testing.PremiumReportFixtures
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/** Writes synthetic company-page previews to build/premium-report-preview/ for manual visual review (pdftoppm). */
class PremiumCompanyPreviewTest {
    @Test
    fun `write company previews`() {
        val dir = File("build/premium-report-preview").apply { mkdirs() }
        val section = PremiumCompanySection()
        val a = PremiumReportFixtures.dataset()
        val cases = mapOf(
            "company-a" to a,
            "company-b" to PremiumReportFixtures.dataset(StudySnapshot.unavailable(PremiumReportFixtures.CUPS, "not_captured")),
            "company-c" to a.copy(comparison = a.comparison.copy(tariffName = "2.0TD", supplyHolder = "Otra Titular S.A.")),
            "company-d" to PremiumReportFixtures.dataset(
                holder = "Compañía Eléctrica Ñandú Peñíscola Muñoz Ibáñez ".repeat(5).trim(),
                address = "Avenida de la Señora Begoña Núñez número 123 escalera izquierda ".repeat(5).trim()
            )
        )
        cases.forEach { (name, ds) ->
            val file = File(dir, "$name.pdf")
            file.writeBytes(PremiumReportPdf.render(listOf(section), ds))
            assertTrue(file.length() > 0)
        }
    }
}
