package com.bm.backend.services.premiumreport

import com.bm.backend.models.PeriodValues
import com.bm.backend.models.StudySnapshot
import com.bm.backend.testing.PdfInspection
import com.bm.backend.testing.PremiumReportFixtures
import com.bm.backend.testing.PremiumReportFixtures.CUPS
import com.bm.backend.testing.PremiumReportFixtures.dataset
import com.bm.backend.models.ReportDataset
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.Locale

class PremiumCoverSectionTest {
    private val cover = PremiumCoverSection(PremiumReportFixtures.CLOCK)

    /** Text extraction may drop the space between a label cell and its value cell. */
    private fun String.hasRow(label: String, value: String) = "$label $value" in this || "$label$value" in this

    private fun render(dataset: ReportDataset) = PdfInspection(PremiumReportPdf.render(listOf(cover), dataset))

    @Test
    fun `A full snapshot renders a one-page portrait cover with every supply row`() {
        val pdf = render(dataset())
        assertTrue(pdf.isPdf)
        assertEquals(1, pdf.pageCount)
        assertEquals(listOf(true), pdf.portraitPages)
        val text = pdf.text
        val ds = dataset()
        listOf(
            "Titular del suministro:" to "CLIENTE PRUEBA S.L.",
            "Dirección del suministro:" to "CALLE FICTICIA 1, 28001 MADRID",
            "CUPS:" to CUPS,
            "Tarifa de acceso:" to ds.comparison.tariffName,
            "Distribuidora:" to "Distribuidora Ficticia S.A.",
            "Ubicación:" to "Madrid (Madrid) — CP 28001",
            "Potencia contratada:" to "P1: 15,1 kW · P2: 15,1 kW · P3: 20 kW · P4: 20 kW · P5: 34 kW · P6: 34,5 kW",
            "Fecha de la última lectura:" to "31/07/2026"
        ).forEach { (label, value) -> assertTrue(text.hasRow(label, value), "missing '$label $value' in: $text") }
        listOf(
            "ESTUDIO COMPLETO DE SUMINISTRO ELÉCTRICO",
            "Análisis de consumo y oferta económica",
            "Fecha del informe: 29/10/2026",
            "DATOS DEL SUMINISTRO"
        ).forEach { assertTrue(it in text, "missing '$it' in: $text") }
        assertEquals(1, Regex(CUPS).findAll(text).count())
        assertFalse("No se dispone de datos adicionales" in text)
    }

    @Test
    fun `B partial snapshot omits absent rows without placeholders`() {
        val fields = PremiumReportFixtures.fullFields().copy(
            postalCode = null, province = " ", municipality = null, lastReadingDate = null,
            contractedPowerKw = PeriodValues(15.1, 20.0, null, null, null, null)
        )
        val text = render(dataset(PremiumReportFixtures.available(fields))).text
        assertTrue(text.hasRow("Distribuidora:", "Distribuidora Ficticia S.A."), text)
        assertTrue(text.hasRow("Potencia contratada:", "P1: 15,1 kW · P2: 20 kW"), text)
        listOf("Ubicación:", "Fecha de la última lectura:", "P3", "P6", "null", " - ", ": 0 kW").forEach {
            assertFalse(it in text, "unexpected '$it' in: $text")
        }
    }

    @Test
    fun `C unavailable snapshot shows identity rows and one neutral sentence`() {
        val text = render(dataset(StudySnapshot.unavailable(CUPS, "not_captured"))).text
        listOf("Titular del suministro:", "Dirección del suministro:", "CUPS:", "Tarifa de acceso:",
            "No se dispone de datos adicionales del suministro.").forEach { assertTrue(it in text, it) }
        listOf("Distribuidora:", "Ubicación:", "Potencia contratada:", "Fecha de la última lectura:", "not_captured")
            .forEach { assertFalse(it in text, it) }
    }

    @Test
    fun `D long identity wraps on one page without clipping`() {
        val holder = ("Compañía Eléctrica Ñandú Peñíscola Muñoz Ibáñez " .repeat(5)).take(200).trim()
        val address = ("Avenida de la Señora Begoña Núñez número 123 escalera izquierda " .repeat(5)).take(300).trim()
        val pdf = render(dataset(holder = holder, address = address))
        assertEquals(1, pdf.pageCount)
        val text = pdf.text
        val es = Locale("es", "ES")
        (holder.uppercase(es).split(" ") + address.uppercase(es).split(" ")).filter { it.isNotBlank() }
            .forEach { assertTrue(it in text, "missing word '$it'") }
        assertTrue(holder.uppercase(es).split(" ").last() in text)
    }

    @Test
    fun `E no technical codes, commercial values or diagnosis leak`() {
        val ds = dataset()
        val pdf = render(ds)
        val text = pdf.text
        val forbidden = mutableListOf("0021", "77,7", "88,8", "66,6", "55,5", "77.7", "54321", "54.321",
            ds.jobId, "MISMATCH", "mismatch", "penalización", "diagnóstico", "recargo",
            "01/03/2015", "2015-03-01", "15/01/2022", "01/06/2020")
        ds.comparison.proposals.forEach { forbidden += it.title; forbidden += it.totalAnnualPrice }
        forbidden.forEach { assertFalse(it in text, "leaked '$it'") }
        val info = pdf.info.values.joinToString(" ")
        listOf(CUPS, "Cliente Prueba", "CLIENTE PRUEBA", "Calle Ficticia", "CALLE FICTICIA")
            .forEach { assertFalse(it in info, "metadata leaked '$it'") }
        assertEquals("BM", pdf.info["Author"])
        assertEquals(PremiumReportTexts.TITLE, pdf.info["Title"])
    }

    @Test
    fun `F every font is embedded Liberation Sans`() {
        val fonts = render(dataset()).fonts
        assertTrue(fonts.isNotEmpty())
        fonts.forEach { (name, embedded) ->
            assertTrue(embedded, "font $name not embedded")
            assertTrue("LiberationSans" in name, "unexpected font $name")
        }
    }

    @Test
    fun `G missing logo fails loudly`() {
        val broken = PremiumCoverSection(PremiumReportFixtures.CLOCK, logoResource = "/images/does_not_exist.png")
        assertThrows<IllegalStateException> { PremiumReportPdf.render(listOf(broken), dataset()) }
        assertTrue(PremiumCoverSection::class.java.getResource(PremiumCoverSection.LOGO_RESOURCE) != null)
    }
}
