package com.bm.backend.services.premiumreport

import com.bm.backend.models.ReportDataset
import com.bm.backend.models.StudySnapshot
import com.bm.backend.testing.PdfInspection
import com.bm.backend.testing.PremiumReportFixtures
import com.bm.backend.testing.PremiumReportFixtures.CUPS
import com.bm.backend.testing.PremiumReportFixtures.dataset
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PremiumInformationSectionTest {
    private val section = PremiumInformationSection()

    private fun render(ds: ReportDataset, s: PremiumReportSection = section) = PremiumReportPdf.render(listOf(s), ds)
    /** OpenPDF stamps /CreationDate, /ModDate, a random /ID and a random font-subset tag (XXXXXX+) per render; mask only those. */
    private fun stable(bytes: ByteArray): ByteArray = String(bytes, Charsets.ISO_8859_1)
        .replace(Regex("/(CreationDate|ModDate)\\s*\\([^)]*\\)"), "/$1()")
        .replace(Regex("/ID\\s*\\[[^\\]]*\\]"), "/ID[]")
        .replace(Regex("/[A-Z]{6}\\+LiberationSans"), "/SUBSET+LiberationSans")
        .toByteArray(Charsets.ISO_8859_1)

    private fun norm(text: String) = text.replace(Regex("\\s+"), " ").trim()

    private fun scenarios(): Map<String, ReportDataset> = mapOf(
        "A" to dataset(),
        "B" to dataset(StudySnapshot.unavailable(CUPS, "not_captured")),
        "C" to dataset(holder = "Otra Titular S.A.", address = "Plaza Inventada 9, 41001 Sevilla").let {
            it.copy(cups = "ES1111111111111111BB0F", comparison = it.comparison.copy(cups = "ES1111111111111111BB0F", tariffName = "2.0TD"))
        },
        "D" to dataset(
            holder = "Compañía Eléctrica Ñandú Peñíscola Muñoz Ibáñez ".repeat(5).trim(),
            address = "Avenida de la Señora Begoña Núñez número 123 escalera izquierda ".repeat(5).trim()
        ).let { ds ->
            val base = ds.comparison.proposals.first()
            ds.copy(comparison = ds.comparison.copy(proposals = (1..15).map { base.copy(title = "Oferta sintética $it") }))
        }
    )

    @Test
    fun `catalogue - page text equals the approved strings in order and nothing else`() {
        val pdf = PdfInspection(render(dataset()))
        assertEquals(listOf(true), pdf.portraitPages)
        assertEquals(norm(PremiumInformationTexts.ORDERED.joinToString(" ")), norm(pdf.text))
        assertEquals("INFORMACIÓN Y MANTENIMIENTO DE SU PÓLIZA ELÉCTRICA", PremiumInformationTexts.ORDERED.first())
    }

    @Test
    fun `catalogue - services 1 to 5 and advice 1 to 7 in order, rebranded, no web or data-protection text`() {
        assertEquals((1..5).map { "$it.-" }, PremiumInformationTexts.SERVICES.map { it.substringBefore(' ') })
        assertEquals((1..7).map { "$it.-" }, PremiumInformationTexts.ADVICE.map { it.substringBefore(' ') })
        assertTrue(PremiumInformationTexts.INTRO.first().startsWith("Desde PowerApp queremos agradecerle"))
        val all = PremiumInformationTexts.ORDERED.joinToString(" ").lowercase()
        listOf("briel", "marnysos", "www.", "trayectoria", "protección de datos", "**").forEach { assertFalse(it in all, it) }
    }

    @Test
    fun `no customer data - identical text for every dataset and byte-identical renders`() {
        val texts = scenarios().mapValues { (_, ds) ->
            val pdf = PdfInspection(render(ds))
            val text = pdf.text
            val forbidden = mutableListOf(ds.comparison.supplyHolder, ds.comparison.supplyAddress, ds.cups, ds.comparison.cups,
                ds.comparison.tariffName, ds.jobId)
            ds.comparison.proposals.forEach { forbidden += it.title; forbidden += it.totalAnnualPrice }
            forbidden.filter { it.isNotBlank() }.forEach {
                assertFalse(it in text || it.uppercase() in text, "leaked '$it'")
            }
            assertFalse(Regex("\\d+,\\d{2}").containsMatchIn(text), "amount leaked")
            pdf.pageCount to norm(text)
        }
        assertEquals(1, texts.values.map { it }.toSet().size, "texts differ: $texts")
        val reference = stable(render(dataset()))
        scenarios().forEach { (name, ds) -> assertArrayEquals(reference, stable(render(ds)), "scenario $name bytes differ from A") }
    }

    @Test
    fun `fonts - only embedded Liberation Sans`() {
        val fonts = PdfInspection(render(dataset())).fonts
        assertTrue(fonts.isNotEmpty())
        fonts.forEach { (name, embedded) ->
            assertTrue(embedded, "font $name not embedded")
            assertTrue("LiberationSans" in name, "unexpected font $name")
        }
    }
}
