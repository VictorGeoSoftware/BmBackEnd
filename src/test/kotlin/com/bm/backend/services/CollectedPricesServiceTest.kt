package com.bm.backend.services

import com.bm.backend.models.CollectedPriceSubmission
import com.bm.backend.testing.InMemoryCollectedPricesRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Unit tests for the collection policy and normalization rules. */
class CollectedPricesServiceTest {

    private lateinit var repository: InMemoryCollectedPricesRepository
    private lateinit var service: CollectedPricesService

    private val fixedClock: Clock =
        Clock.fixed(Instant.parse("2026-08-17T10:15:30Z"), ZoneOffset.UTC)

    @BeforeEach
    fun setUp() {
        repository = InMemoryCollectedPricesRepository()
        service = CollectedPricesService(repository, fixedClock)
    }

    private fun submission(
        companyName: String = "Iberdrola",
        tariffType: String = "3.0TD",
        powerPrices: Map<String, Double> = mapOf("P1" to 1.5, "P2" to 2.5),
        energyPrices: Map<String, Double> = mapOf("P1" to 0.1),
        extraServices: Double? = null
    ) = CollectedPriceSubmission(
        companyName = companyName,
        tariffType = tariffType,
        powerPricesByPeriod = powerPrices,
        energyPricesByPeriod = energyPrices,
        extraServices = extraServices
    )

    @Test
    fun `submit stores a non-2 0TD submission`() {
        val result = service.submit(submission())

        assertIs<CollectedPricesService.SubmitResult.Stored>(result)
        assertEquals(1, repository.stored.size)
        assertEquals("3.0TD", repository.stored.single().tariffType)
        assertEquals(Instant.parse("2026-08-17T10:15:30Z"), repository.stored.single().collectedAt)
    }

    @Test
    fun `submit rejects 2 0TD regardless of spacing casing or punctuation`() {
        val variants = listOf("2.0TD", "2.0 TD", " 2.0td ", "2-0-TD", "20TD")

        variants.forEach { variant ->
            val result = service.submit(submission(tariffType = variant))
            assertIs<CollectedPricesService.SubmitResult.ExcludedTariff>(
                result,
                "expected '$variant' to be excluded"
            )
        }

        assertTrue(repository.stored.isEmpty())
    }

    @Test
    fun `submit does not exclude 3 1TD`() {
        assertIs<CollectedPricesService.SubmitResult.Stored>(
            service.submit(submission(tariffType = "3.1TD"))
        )
    }

    @Test
    fun `submit expands partial period maps into dense P1 to P6 lists`() {
        service.submit(
            submission(
                powerPrices = mapOf("P1" to 1.0, "P3" to 3.0),
                energyPrices = mapOf("P6" to 6.0)
            )
        )

        val stored = repository.stored.single()
        assertEquals(listOf(1.0, null, 3.0, null, null, null), stored.powerPrices)
        assertEquals(listOf(null, null, null, null, null, 6.0), stored.energyPrices)
        assertNull(stored.extraServices)
    }

    @Test
    fun `submit keeps the typed company name but normalizes for grouping`() {
        service.submit(submission(companyName = "  Iberdrola   S.A. "))

        val stored = repository.stored.single()
        assertEquals("Iberdrola S.A.", stored.companyName)
        assertEquals("iberdrola s.a.", stored.companyNameNormalized)
    }

    @Test
    fun `submit stores tariff uppercased and trimmed but readable`() {
        service.submit(submission(tariffType = " 3.0td "))

        assertEquals("3.0TD", repository.stored.single().tariffType)
    }

    @Test
    fun `list clamps limit to the maximum page size`() {
        val response = service.list(
            limit = 10_000,
            offset = 0,
            tariffType = null,
            companyName = null
        )

        assertEquals(CollectedPricesService.MAX_PAGE_SIZE, response.limit)
    }

    @Test
    fun `list clamps a negative offset to zero`() {
        val response = service.list(limit = 10, offset = -5, tariffType = null, companyName = null)

        assertEquals(0, response.offset)
    }

    @Test
    fun `list reports the unpaginated total alongside the page`() {
        repeat(5) { index ->
            service.submit(submission(companyName = "Company $index"))
        }

        val response = service.list(limit = 2, offset = 0, tariffType = null, companyName = null)

        assertEquals(2, response.items.size)
        assertEquals(5L, response.total)
    }

    @Test
    fun `list filters by company using the canonical form`() {
        service.submit(submission(companyName = "Iberdrola"))
        service.submit(submission(companyName = "Endesa"))

        val response = service.list(
            limit = 50,
            offset = 0,
            tariffType = null,
            companyName = "  IBERDROLA  "
        )

        assertEquals(1, response.items.size)
        assertEquals(1L, response.total)
        assertEquals("Iberdrola", response.items.single().companyName)
    }

    @Test
    fun `list treats a blank filter as no filter`() {
        service.submit(submission())

        val response = service.list(limit = 50, offset = 0, tariffType = "  ", companyName = "")

        assertEquals(1, response.items.size)
    }

    @Test
    fun `list exposes collectedAt as epoch millis`() {
        service.submit(submission())

        val response = service.list(limit = 50, offset = 0, tariffType = null, companyName = null)

        assertEquals(
            Instant.parse("2026-08-17T10:15:30Z").toEpochMilli(),
            response.items.single().collectedAt
        )
    }
}
