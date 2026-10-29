package com.bm.backend.utils

import com.bm.backend.models.PeriodValues
import com.bm.backend.models.StudyHistory
import com.bm.backend.models.StudyHistoryStatus
import com.bm.backend.models.StudySnapshot
import com.bm.backend.models.TariffProfileStatus
import com.bm.backend.testing.PremiumReportFixtures as F
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TariffPeriodResolverTest {
    private val p13 = listOf(1, 2, 3)
    private val p12 = listOf(1, 2)
    private val all = (1..6).toList()

    @Test
    fun `normalise accepts suffixes, case, comma and underscore`() {
        assertEquals("3.0TD", TariffPeriodResolver.normalise("3.0TD ML"))
        assertEquals("2.0TD", TariffPeriodResolver.normalise(" 2.0td "))
        assertEquals("2.0TD", TariffPeriodResolver.normalise("2,0TD"))
        assertEquals("3.0TD", TariffPeriodResolver.normalise("3_0TD"))
        assertEquals("6.1TD", TariffPeriodResolver.normalise("6.1TD"))
        assertNull(TariffPeriodResolver.normalise(""))
        assertNull(TariffPeriodResolver.normalise("fixed"))
        assertNull(TariffPeriodResolver.normalise(null))
    }

    @Test
    fun `supported table is the only tariff definition`() {
        assertEquals(setOf("2.0TD", "3.0TD"), TariffPeriodResolver.SUPPORTED.keys)
        assertEquals(p13 to p12, TariffPeriodResolver.SUPPORTED["2.0TD"])
        assertEquals(all to all, TariffPeriodResolver.SUPPORTED["3.0TD"])
    }

    @Test
    fun `decision table`() {
        data class Case(val snap: String?, val name: String, val status: TariffProfileStatus, val code: String?, val energy: List<Int>, val power: List<Int>)
        listOf(
            Case("2.0TD", "2.0TD", TariffProfileStatus.SUPPORTED, "2.0TD", p13, p12),
            Case("2.0td", "fixed", TariffProfileStatus.SUPPORTED, "2.0TD", p13, p12),
            Case(null, "2,0TD", TariffProfileStatus.SUPPORTED, "2.0TD", p13, p12),
            Case("", "2.0TD", TariffProfileStatus.SUPPORTED, "2.0TD", p13, p12),
            Case("3.0TD ML", "3.0TD", TariffProfileStatus.SUPPORTED, "3.0TD", all, all),
            Case("6.1TD", "6.1TD", TariffProfileStatus.FALLBACK_UNKNOWN, "6.1TD", p13, p12),
            Case(null, "", TariffProfileStatus.FALLBACK_UNKNOWN, null, p13, p12),
            Case("garbage", "fixed", TariffProfileStatus.FALLBACK_UNKNOWN, null, p13, p12),
            Case("2.0TD", "3.0TD", TariffProfileStatus.FALLBACK_CONFLICT, null, p13, p12),
            Case("6.1TD", "3.0TD", TariffProfileStatus.FALLBACK_CONFLICT, null, p13, p12)
        ).forEach { c ->
            val p = TariffPeriodResolver.resolve(F.tariffDataset(c.snap, c.name))
            assertEquals(c.status, p.status, "$c")
            assertEquals(c.code, p.tariffCode, "$c")
            assertEquals(c.energy, p.energyPeriods, "$c")
            assertEquals(c.power, p.powerPeriods, "$c")
        }
    }

    @Test
    fun `conflict keeps the source texts as published`() {
        val p = TariffPeriodResolver.resolve(F.tariffDataset(" 2.0TD ", "3.0TD ML"))
        assertEquals("2.0TD", p.snapshotTariff)
        assertEquals("3.0TD ML", p.tariffName)
    }

    @Test
    fun `zeros outside the tariff are tolerated, non-zero values force the inconsistent fallback`() {
        val zeros = TariffPeriodResolver.resolve(F.tariffDataset(rows = F.tariff20Rows(zeros = true), zeros = true))
        assertEquals(TariffProfileStatus.SUPPORTED, zeros.status)
        assertEquals(p13, zeros.energyPeriods)
        assertEquals(p12, zeros.powerPeriods)

        val bad = TariffPeriodResolver.resolve(F.tariffDataset(rows = F.tariff20InconsistentRows()))
        assertEquals(TariffProfileStatus.FALLBACK_INCONSISTENT, bad.status)
        assertEquals("2.0TD", bad.tariffCode)
        assertEquals(listOf(1, 2, 3, 5), bad.energyPeriods)
        assertEquals(p12, bad.powerPeriods)
    }

    @Test
    fun `legacy non-zero P5 forces the inconsistent fallback, legacy zeros stay supported`() {
        val bad = TariffPeriodResolver.resolve(F.tariff20LegacyP5Dataset())
        assertEquals(TariffProfileStatus.FALLBACK_INCONSISTENT, bad.status)
        assertEquals("2.0TD", bad.tariffCode)
        assertEquals(listOf(1, 2, 3, 5), bad.energyPeriods)
        assertEquals(listOf(1, 2), bad.powerPeriods)
        val ok = TariffPeriodResolver.resolve(F.tariffDataset())
        assertEquals(0.0, F.tariffDataset().report.consumptionData.annualConsumptionP5)
        assertEquals(TariffProfileStatus.SUPPORTED, ok.status)
    }

    @Test
    fun `snapshot values outside the tariff force the inconsistent fallback`() {
        val ds = F.tariffDataset()
        val fields = F.tariff20Fields().copy(contractedPowerKw = PeriodValues(4.6, 4.6, 5.0, null, null, null))
        val p = TariffPeriodResolver.resolve(ds.copy(snapshot = F.available(fields)))
        assertEquals(TariffProfileStatus.FALLBACK_INCONSISTENT, p.status)
        assertEquals(listOf(1, 2, 3), p.powerPeriods)
    }

    @Test
    fun `fallback with no values anywhere shows P1 to P6, unavailable snapshot is ignored`() {
        val empty = StudyHistory(F.CUPS, StudyHistoryStatus.AVAILABLE, null, emptyList())
        val ds = F.tariffDataset(null, "").copy(history = empty, snapshot = StudySnapshot.unavailable(F.CUPS, "x"))
        val p = TariffPeriodResolver.resolve(ds)
        assertEquals(TariffProfileStatus.FALLBACK_UNKNOWN, p.status)
        assertEquals(all, p.energyPeriods)
        assertEquals(all, p.powerPeriods)
        // an unavailable snapshot's tariff is not evidence; tariffName alone decides.
        val named = TariffPeriodResolver.resolve(F.tariffDataset("3.0TD", "2.0TD").copy(snapshot = StudySnapshot.unavailable(F.CUPS, "x")))
        assertEquals(TariffProfileStatus.SUPPORTED, named.status)
        assertEquals("2.0TD", named.tariffCode)
    }

    @Test
    fun `existing 3_0TD fixtures stay supported with six periods`() {
        listOf(F.documentDataset(), F.documentDataset(F.documentLongRows()), F.documentDataset(F.documentActiveOnlyRows()),
            F.documentDataset(F.demandInactiveRows()), F.documentDataset(F.reactiveNullRows())).forEach { ds ->
            val p = TariffPeriodResolver.resolve(ds)
            assertEquals(TariffProfileStatus.SUPPORTED, p.status)
            assertEquals(all, p.energyPeriods)
            assertEquals(all, p.powerPeriods)
        }
    }
}
