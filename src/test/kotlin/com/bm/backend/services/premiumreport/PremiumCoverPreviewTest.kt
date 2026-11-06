package com.bm.backend.services.premiumreport

import com.bm.backend.models.PeriodValues
import com.bm.backend.models.StudySnapshot
import com.bm.backend.testing.PremiumReportFixtures
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/** Writes synthetic cover previews to build/premium-report-preview/ for manual visual review (pdftoppm). */
class PremiumCoverPreviewTest {
    @Test
    fun `write cover previews`() {
        val dir = File("build/premium-report-preview").apply { mkdirs() }
        val cover = PremiumCoverSection(PremiumReportFixtures.CLOCK)
        val partial = PremiumReportFixtures.fullFields().copy(
            postalCode = null, municipality = null, province = null, lastReadingDate = null,
            contractedPowerKw = PeriodValues(15.1, 20.0, null, null, null, null)
        )
        val cases = mapOf(
            "cover-a-full" to PremiumReportFixtures.dataset(),
            "cover-b-partial" to PremiumReportFixtures.dataset(PremiumReportFixtures.available(partial)),
            "cover-c-unavailable" to PremiumReportFixtures.dataset(StudySnapshot.unavailable(PremiumReportFixtures.CUPS, "not_captured")),
            "cover-d-long" to PremiumReportFixtures.dataset(
                holder = ("Compañía Eléctrica Ñandú Peñíscola Muñoz Ibáñez ".repeat(5)).take(200).trim(),
                address = ("Avenida de la Señora Begoña Núñez número 123 escalera izquierda ".repeat(5)).take(300).trim()
            )
        )
        cases.forEach { (name, ds) ->
            val file = File(dir, "$name.pdf")
            file.writeBytes(PremiumReportPdf.render(listOf(cover), ds))
            assertTrue(file.length() > 0)
        }
    }
}
