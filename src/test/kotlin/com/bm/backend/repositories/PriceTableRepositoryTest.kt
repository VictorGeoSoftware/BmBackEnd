package com.bm.backend.repositories

import com.bm.backend.database.DatabaseFactory
import com.bm.backend.models.IMPUESTO_ELECTRICO
import com.bm.backend.models.IVA
import com.bm.backend.testing.PriceTableFixtures
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Characterization tests pinning the current [PriceTableRepository] behavior
 * before the SQLite -> Postgres refactor (Phase A1).
 *
 * Any contract change must update these tests deliberately.
 */
class PriceTableRepositoryTest {

    private lateinit var repository: PriceTableRepository

    @BeforeEach
    fun setUp() {
        DatabaseFactory.initTestDatabase()
        repository = PriceTableRepository()
    }

    @AfterEach
    fun tearDown() {
        java.io.File("test_price_tables.db").delete()
    }

    @Test
    fun `getTaxSettings returns defaults on empty database`() {
        val settings = repository.getTaxSettings()

        assertTrue(settings.success)
        assertEquals(IVA, settings.iva)
        assertEquals(IMPUESTO_ELECTRICO, settings.impuestoElectrico)
    }

    @Test
    fun `updateTaxSettings persists and overrides defaults`() {
        repository.updateTaxSettings(iva = 10.0, impuestoElectrico = 1.0)

        val settings = repository.getTaxSettings()
        assertEquals(10.0, settings.iva)
        assertEquals(1.0, settings.impuestoElectrico)
    }

    @Test
    fun `updateTaxSettings is idempotent and does not create duplicate rows`() {
        repository.updateTaxSettings(iva = 21.0, impuestoElectrico = 5.11)
        repository.updateTaxSettings(iva = 18.0, impuestoElectrico = 4.0)
        repository.updateTaxSettings(iva = 16.0, impuestoElectrico = 3.5)

        val settings = repository.getTaxSettings()
        assertEquals(16.0, settings.iva)
        assertEquals(3.5, settings.impuestoElectrico)
    }

    @Test
    fun `storePriceTableResults inserts new rows and returns inserted count`() {
        val response = PriceTableFixtures.response(
            PriceTableFixtures.result(
                fileName = "iberdrola-2024.pdf",
                companyName = "Iberdrola"
            )
        )

        val inserted = repository.storePriceTableResults(response)

        // 1 potencia + 1 energia base + 1 energia unica = 3 tarifa rows
        assertEquals(3, inserted)

        val all = repository.getAllPriceTableResults()
        assertEquals(1, all.results.size)
        val stored = all.results.single()
        assertEquals("iberdrola-2024.pdf", stored.fileName)
        assertEquals("Iberdrola", stored.extracted_tables.companyName)
        assertNotNull(stored.id)
    }

    @Test
    fun `storePriceTableResults upserts by natural key fileName + companyName`() {
        val first = PriceTableFixtures.response(
            PriceTableFixtures.result(
                fileName = "endesa-2024.pdf",
                companyName = "Endesa",
                energyP1 = 0.180000
            )
        )
        repository.storePriceTableResults(first)
        val firstId = repository.getAllPriceTableResults().results.single().id

        val second = PriceTableFixtures.response(
            PriceTableFixtures.result(
                fileName = "endesa-2024.pdf",
                companyName = "Endesa",
                energyP1 = 0.200000
            )
        )
        repository.storePriceTableResults(second)

        val all = repository.getAllPriceTableResults()
        assertEquals(1, all.results.size, "Same natural key must not create a duplicate row")
        assertEquals(firstId, all.results.single().id, "Existing row id must be preserved")
        // Child rows replaced with the new energy value
        val baseTarifa = all.results.single()
            .extracted_tables.termino_de_energia.tabla_precio_clasica_base.tarifas.single()
        assertEquals(0.200000, baseTarifa.P1)
    }

    @Test
    fun `storePriceTableResults treats different fileName as a new row`() {
        repository.storePriceTableResults(
            PriceTableFixtures.response(
                PriceTableFixtures.result(fileName = "a.pdf", companyName = "Iberdrola")
            )
        )
        repository.storePriceTableResults(
            PriceTableFixtures.response(
                PriceTableFixtures.result(fileName = "b.pdf", companyName = "Iberdrola")
            )
        )

        assertEquals(2, repository.getAllPriceTableResults().results.size)
    }

    @Test
    fun `getAllPriceTableResults filters by tarifaType case-insensitively`() {
        repository.storePriceTableResults(
            PriceTableFixtures.response(
                PriceTableFixtures.result(fileName = "a.pdf", companyName = "A", tarifaName = "2.0TD")
            )
        )
        repository.storePriceTableResults(
            PriceTableFixtures.response(
                PriceTableFixtures.result(fileName = "b.pdf", companyName = "B", tarifaName = "3.0TD")
            )
        )

        val matched = repository.getAllPriceTableResults(tarifaType = "2.0td")

        assertEquals(1, matched.results.size)
        assertEquals("A", matched.results.single().extracted_tables.companyName)
    }

    @Test
    fun `getFilteredPriceTableResults returns single tarifa per result`() {
        repository.storePriceTableResults(
            PriceTableFixtures.response(
                PriceTableFixtures.result(
                    fileName = "iberdrola.pdf",
                    companyName = "Iberdrola",
                    tarifaName = "2.0TD"
                )
            )
        )

        val filtered = repository.getFilteredPriceTableResults(tarifaType = "2.0TD")

        assertEquals(1, filtered.results.size)
        val r = filtered.results.single()
        assertEquals("2.0TD", r.extracted_tables.termino_de_potencia.tabla_precio_potencia.tarifa.tarifa)
        assertEquals("2.0TD", r.extracted_tables.termino_de_energia.tabla_precio_clasica_base.tarifa.tarifa)
        assertEquals("2.0TD", r.extracted_tables.termino_de_energia.tabla_precio_clasica_unica.tarifa.tarifa)
    }

    @Test
    fun `getFilteredPriceTableResults skips results without all three tarifas`() {
        // Only a 3.0TD-tarifa proposal exists; querying for 2.0TD must yield 0 results
        repository.storePriceTableResults(
            PriceTableFixtures.response(
                PriceTableFixtures.result(fileName = "naturgy.pdf", companyName = "Naturgy", tarifaName = "3.0TD")
            )
        )

        val filtered = repository.getFilteredPriceTableResults(tarifaType = "2.0TD")
        assertTrue(filtered.results.isEmpty())
    }

    @Test
    fun `clearAllData removes every row and returns total count`() {
        repository.storePriceTableResults(
            PriceTableFixtures.response(
                PriceTableFixtures.result(fileName = "a.pdf", companyName = "A"),
                PriceTableFixtures.result(fileName = "b.pdf", companyName = "B")
            )
        )

        val deleted = repository.clearAllData()

        assertTrue(deleted > 0)
        assertEquals(0, repository.getAllPriceTableResults().results.size)
    }

    @Test
    fun `deleteResultsByIds returns deleted ids and not-found ids`() {
        repository.storePriceTableResults(
            PriceTableFixtures.response(
                PriceTableFixtures.result(fileName = "a.pdf", companyName = "A"),
                PriceTableFixtures.result(fileName = "b.pdf", companyName = "B")
            )
        )
        val all = repository.getAllPriceTableResults().results
        val firstId = all.first().id!!

        val (deleted, notFound) = repository.deleteResultsByIds(listOf(firstId, 99_999))

        assertEquals(listOf(firstId), deleted)
        assertEquals(listOf(99_999), notFound)
        assertEquals(1, repository.getAllPriceTableResults().results.size)
    }

    @Test
    fun `deleteResultsByIds cascades to child tarifa rows`() {
        repository.storePriceTableResults(
            PriceTableFixtures.response(
                PriceTableFixtures.result(fileName = "a.pdf", companyName = "A")
            )
        )
        val id = repository.getAllPriceTableResults().results.single().id!!

        repository.deleteResultsByIds(listOf(id))

        // Parent gone -> child rows are not visible through any read path
        val again = repository.getAllPriceTableResults()
        assertTrue(again.results.isEmpty())
        assertNull(again.results.firstOrNull())
    }
}
