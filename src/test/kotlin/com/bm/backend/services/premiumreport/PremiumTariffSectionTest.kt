package com.bm.backend.services.premiumreport

import com.bm.backend.models.ReportDataset
import com.bm.backend.testing.PdfInspection
import com.bm.backend.testing.PremiumReportFixtures as F
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Scenarios 1–9 of spec report-tariff-applicability on the assembled Premium document (synthetic data). */
class PremiumTariffSectionTest {
    private val renderer = PdfPremiumReportRenderer(F.CLOCK)
    private fun render(ds: ReportDataset) = PdfInspection(renderer.render(ds))

    private val t1 = PremiumReportTexts.TARIFF_UNKNOWN
    private val sourcePeriods = "se muestran los periodos presentes en la fuente."

    /** Text from the first page containing [from] up to (excluding) the first later page containing [to]. */
    private fun PdfInspection.between(from: String, to: String): String {
        val a = pageTexts.indexOfFirst { it.contains(from) }
        val b = pageTexts.indexOfFirst { it.contains(to) }
        assertTrue(a in 0 until b, "$from@$a $to@$b")
        return pageTexts.subList(a, b).joinToString(" ")
    }

    private fun assert20(pdf: PdfInspection, ds: ReportDataset) {
        val text = pdf.text
        listOf("P4", "P5", "P6").forEach { assertFalse(text.contains(it), "$it rendered for 2.0TD") }
        assertFalse(text.contains(sourcePeriods), "no T1–T3 for a supported tariff")
        assertTrue(pdf.pageTexts[0].contains("Potencia contratada:P1: 4,6 kW · P2: 4,6 kW Fecha"), pdf.pageTexts[0])

        val active = pdf.between(PremiumActiveTexts.SECTION, PremiumReactiveTexts.SECTION)
        listOf("P1", "P2", "P3").forEach { assertTrue(active.contains("$it (kWh)"), it) }

        val reactive = pdf.between(PremiumReactiveTexts.SECTION, PremiumDemandTexts.SECTION)
        assertFalse(reactive.contains(PremiumReactiveTexts.NULL_CELLS))
        assertFalse(reactive.contains(PremiumReactiveTexts.RATIO_NULL_CELLS))
        assertTrue(reactive.contains("P3${PremiumReactiveTexts.UNIT_SUFFIX}"))

        val demand = pdf.between(PremiumDemandTexts.SECTION, ds.comparison.title)
        assertFalse(demand.contains("Sin lecturas en la fuente para"))
        assertFalse(demand.contains(PremiumDemandTexts.NULL_CELLS))
        assertFalse(demand.contains("P3"), "demand shows only P1–P2")
        assertTrue(demand.contains(PremiumDemandTexts.readingsCaption(listOf(1, 2))))
        assertTrue(demand.contains("entre P1 y P2"))
        assertTrue(demand.contains("P2${PremiumDemandTexts.UNIT_SUFFIX}"))
    }

    @Test
    fun `1 two point zero TD with null P4 to P6`() {
        val ds = F.tariffDataset()
        assert20(render(ds), ds)
    }

    @Test
    fun `2 two point zero TD with zero P4 to P6 renders like nulls`() {
        val nulls = F.tariffDataset()
        val zeros = F.tariffDataset(rows = F.tariff20Rows(zeros = true), zeros = true)
        val z = render(zeros)
        assert20(z, zeros)
        assertEquals(render(nulls).text, z.text)
    }

    @Test
    fun `3 three point zero TD keeps six periods and no tariff note`() {
        val pdf = render(F.documentDataset())
        assertFalse(pdf.text.contains(sourcePeriods))
        assertTrue(pdf.text.contains(PremiumDemandTexts.readingsCaption((1..6).toList())))
        assertTrue(pdf.text.contains("entre P1 y P6"))
        assertTrue(pdf.text.contains("P6 (kWh)"))
    }

    private fun assertCoverOnly(pdf: PdfInspection, sentence: String) {
        assertTrue(pdf.pageTexts[0].contains(sentence), pdf.pageTexts[0])
        assertEquals(1, Regex(Regex.escape(sentence)).findAll(pdf.text).count())
        pdf.pageTexts.drop(1).forEach { assertFalse(it.contains(sourcePeriods)) }
    }

    @Test
    fun `4 unknown tariff discloses T1 and shows the periods present in the source`() {
        listOf(F.tariffDataset("6.1TD", "6.1TD"), F.tariffDataset(null, "")).forEach { ds ->
            val pdf = render(ds)
            assertCoverOnly(pdf, t1)
            listOf("P4", "P5", "P6").forEach { assertFalse(pdf.text.contains(it), it) }
        }
    }

    @Test
    fun `4b unknown tariff after the unavailable-snapshot sentence`() {
        val ds = F.tariffDataset(null, "fixed").copy(snapshot = com.bm.backend.models.StudySnapshot.unavailable(F.CUPS, "x"))
        val cover = render(ds).pageTexts[0]
        assertTrue(cover.contains("${PremiumReportTexts.SNAPSHOT_UNAVAILABLE} $t1"), cover)
    }

    @Test
    fun `5 conflicting tariffs disclose T2 with the source texts`() {
        val pdf = render(F.tariffDataset("2.0TD", "3.0TD"))
        assertCoverOnly(pdf, "Las fuentes indican tarifas distintas (2.0TD / 3.0TD): se muestran los periodos presentes en la fuente.")
    }

    @Test
    fun `6 out-of-tariff value discloses T3 and is shown`() {
        val pdf = render(F.tariffDataset(rows = F.tariff20InconsistentRows()))
        assertCoverOnly(pdf, "La fuente publica valores en periodos que no corresponden a la tarifa 2.0TD: se muestran los periodos presentes en la fuente.")
        val active = pdf.between(PremiumActiveTexts.SECTION, PremiumReactiveTexts.SECTION)
        assertTrue(active.contains("P5 (kWh)"))
        assertTrue(active.contains("120"))
        assertFalse(active.contains("P4 (kWh)"))
    }

    @Test
    fun `6b legacy out-of-tariff value discloses T3 and is shown`() {
        val pdf = render(F.tariff20LegacyP5Dataset())
        assertCoverOnly(pdf, PremiumReportTexts.tariffInconsistent("2.0TD"))
        val active = pdf.between(PremiumActiveTexts.SECTION, PremiumReactiveTexts.SECTION)
        assertTrue(active.contains("P5 (kWh)"))
        val legacy = active.substringAfter("Consumo anual del estudio básico")
        val rows = Regex("P(\\d) ([\\d.]+) ").findAll(legacy.substringBefore("Total")).map { it.groupValues[1] to it.groupValues[2].replace(".", "").toInt() }.toList()
        assertEquals(listOf("1", "2", "3", "5"), rows.map { it.first }, legacy)
        val total = Regex("Total ([\\d.]+) ").find(legacy)!!.groupValues[1].replace(".", "").toInt()
        assertEquals(3120, total)
        assertEquals(total, rows.sumOf { it.second })
    }

    @Test
    fun `7 comparison page renders exactly the request rows`() {
        val ds = F.tariffDataset()
        val pdf = render(ds)
        val page = pdf.pageTexts.first { it.contains(ds.comparison.title) }
        assertTrue(page.contains("P1") && page.contains("P2") && page.contains("P3"))
        assertFalse(page.contains("P4"))
    }

    @Test
    fun `T4 caption variants`() {
        val c6 = PremiumDemandTexts.readingsCaption((1..6).toList())
        assertEquals(
            "Cada barra es una lectura de la fuente (no un mes): la altura es el mayor valor de maxímetro registrado entre P1 y P6 " +
                "en esa lectura (kW) y la anchura los días que cubre. Contorno sin relleno: lectura solapada. Raya sobre el eje: lectura con valor 0.",
            c6
        )
        assertEquals(c6, PremiumDemandTexts.READINGS_CAPTION)
        assertTrue(PremiumDemandTexts.readingsCaption(listOf(1, 2)).contains("registrado entre P1 y P2 en esa lectura (kW)"))
        assertTrue(PremiumDemandTexts.readingsCaption(listOf(2)).contains("registrado en P2 en esa lectura (kW)"))
        assertTrue(PremiumDemandTexts.readingsCaption(listOf(1, 3, 5)).contains("registrado en P1, P3, P5 en esa lectura (kW)"))
    }

    @Test
    fun `T1 to T3 texts are verbatim`() {
        assertEquals("Tarifa no reconocida para este informe: se muestran los periodos presentes en la fuente.", PremiumReportTexts.TARIFF_UNKNOWN)
        assertEquals("Las fuentes indican tarifas distintas (A / B): se muestran los periodos presentes en la fuente.", PremiumReportTexts.tariffConflict("A", "B"))
        assertEquals("La fuente publica valores en periodos que no corresponden a la tarifa 2.0TD: se muestran los periodos presentes en la fuente.",
            PremiumReportTexts.tariffInconsistent("2.0TD"))
    }

    @Test
    fun `N8 condition uses the profile size`() {
        // 2.0TD with P2 maximeter never recorded: N8 lists P2 only (1 < 2).
        val rows = F.tariff20Rows().map { it.copy(maxDemand = it.maxDemand.copy(p2 = null)) }
        val ds = F.tariffDataset(rows = rows)
        val demand = render(ds).between(PremiumDemandTexts.SECTION, ds.comparison.title)
        assertTrue(demand.contains(PremiumDemandTexts.noReadings(listOf(2))), demand)
    }

    @Test
    fun `9 one generator for every tariff`() {
        val a = renderer.defaultSections(F.tariffDataset()).map { it::class }
        val b = renderer.defaultSections(F.documentDataset()).map { it::class }
        assertEquals(b, a)
    }
}
