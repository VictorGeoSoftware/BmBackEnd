package com.bm.backend.services.premiumreport

import com.bm.backend.models.ReportDataset
import com.bm.backend.models.StudySnapshot
import com.bm.backend.services.ComparatorReportPdfTexts
import com.bm.backend.testing.PdfInspection
import com.bm.backend.testing.PremiumReportFixtures
import com.bm.backend.testing.PremiumReportFixtures.CUPS
import com.bm.backend.testing.PremiumReportFixtures.dataset
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class PremiumCompanySectionTest {
    private val section = PremiumCompanySection()

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
        val expected = norm(PremiumCompanyTexts.ORDERED.joinToString(" "))
        assertEquals(expected, norm(pdf.text))
        assertEquals(
            listOf(
                "INFORMACIÓN SOBRE PROTECCIÓN DE DATOS",
                "BRIEL MARNYSOS CONSULTORÍA ENERGÉTICA S.L.U.",
                ComparatorReportPdfTexts.DATA_PROTECTION_DISCLAIMER
            ),
            PremiumCompanyTexts.ORDERED
        )
    }

    @Test
    fun `catalogue - texts object declares exactly the approved strings`() {
        val strings = PremiumCompanyTexts::class.java.declaredFields
            .filter { it.type == String::class.java }
            .map { it.isAccessible = true; it.get(PremiumCompanyTexts) as String }
            .toSet()
        assertEquals(setOf("INFORMACIÓN SOBRE PROTECCIÓN DE DATOS", "BRIEL MARNYSOS CONSULTORÍA ENERGÉTICA S.L.U."), strings)
    }

    @Test
    fun `verbatim - data protection text is the unchanged constant`() {
        val literal = "BRIEL MARNYSOS CONSULTORÍA ENERGÉTICA S.L.U., como Responsable del tratamiento, de los datos personales proporcionados para la ejecución de la relación precontractual o contractual, le informa que sus datos serán tratados de conformidad con lo dispuesto en el Reglamento General (UE) 2016/679, de protección de datos (RGPD) y en la L.O. 3/2018, de protección de datos y garantía de los derechos digitales (LOPDGDD), con la finalidad de poder cumplir con las obligaciones mercantiles, contables y fiscales, y serán conservadas durante un plazo mínimo de 6 años por obligación legal, procediéndose posteriormente a su eliminación con medidas de seguridad adecuadas para garantizar la seudonimización de los datos o la destrucción total de los mismos. Los datos podrán ser cedidos a empresas energéticas o colaboradores por ser necesario para prestarle el servicio. Por último le informamos que puede ejercer los derechos de acceso, rectificación, portabilidad y supresión de sus datos, y los de limitación y oposición a su tratamiento, dirigiendo comunicación, motivada y acreditada, a dpo@brielmarnysos.com, o presentar una reclamación ante la autoridad competente (www.aepd.es)"
        assertEquals(literal, ComparatorReportPdfTexts.DATA_PROTECTION_DISCLAIMER)
        assertTrue(norm(PdfInspection(render(dataset())).text).endsWith(norm(literal)))
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
    fun `forbidden vocabulary absent`() {
        val text = norm(PdfInspection(render(dataset())).text)
            .replace(norm(ComparatorReportPdfTexts.DATA_PROTECTION_DISCLAIMER), "").lowercase()
        listOf("penaliz", "recargo", "garantiz", "ahorro garantizado", "recomend", "optimi", "diagn",
            "años de experiencia", "clientes", "www.brielmarnysos.com").forEach { assertFalse(it in text, "found '$it'") }
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

    @Test
    fun `E missing logo fails loudly`() {
        val broken = PremiumCompanySection(logoResource = "/images/does_not_exist.png")
        assertThrows<IllegalStateException> { render(dataset(), broken) }
    }
}
