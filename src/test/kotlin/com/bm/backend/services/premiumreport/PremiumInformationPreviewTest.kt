package com.bm.backend.services.premiumreport

import com.bm.backend.models.StudySnapshot
import com.bm.backend.testing.PremiumReportFixtures
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/** Writes synthetic information-page previews to build/premium-report-preview/ for manual visual review (pdftoppm). */
class PremiumInformationPreviewTest {
    @Test
    fun `write company previews`() {
        val dir = File("build/premium-report-preview").apply { mkdirs() }
        val section = PremiumInformationSection()
        val a = PremiumReportFixtures.dataset()
        val cases = mapOf(
            "information-a" to a,
            "information-b" to PremiumReportFixtures.dataset(StudySnapshot.unavailable(PremiumReportFixtures.CUPS, "not_captured")),
            "information-c" to a.copy(comparison = a.comparison.copy(tariffName = "2.0TD", supplyHolder = "Otra Titular S.A.")),
            "information-d" to PremiumReportFixtures.dataset(
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
