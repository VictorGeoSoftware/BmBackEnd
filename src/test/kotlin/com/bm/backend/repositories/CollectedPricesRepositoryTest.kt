package com.bm.backend.repositories

import com.bm.backend.models.CollectedPrice
import com.bm.backend.testing.DockerAvailable
import com.bm.backend.testing.PostgresTestSetup
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** Postgres-backed contract tests for CollectedPricesRepository. */
class CollectedPricesRepositoryTest {

    companion object {
        @BeforeAll
        @JvmStatic
        fun startPostgres() {
            Assumptions.assumeTrue(DockerAvailable.check(), "Docker not available")
            PostgresTestSetup.ensureStarted()
        }
    }

    private val repository = CollectedPricesRepository()

    @BeforeEach
    fun resetSchema() {
        PostgresTestSetup.resetSchema()
    }

    private fun collectedPrice(
        companyName: String = "Iberdrola",
        tariffType: String = "3.0TD",
        powerPrices: List<Double?> = listOf(1.0, 2.0, 3.0, null, null, null),
        energyPrices: List<Double?> = listOf(0.1, null, null, null, null, null),
        extraServices: Double? = 25.0,
        collectedAt: Instant = Instant.now().truncatedTo(ChronoUnit.MILLIS)
    ) = CollectedPrice(
        companyName = companyName,
        companyNameNormalized = companyName.lowercase(),
        tariffType = tariffType,
        powerPrices = powerPrices,
        energyPrices = energyPrices,
        extraServices = extraServices,
        collectedAt = collectedAt
    )

    @Test
    fun `insert returns the row with a generated id and round-trips every column`() {
        val inserted = repository.insert(collectedPrice())
        assertNotNull(inserted.id)

        val fetched = repository.findPage(limit = 10, offset = 0).single()
        assertEquals(inserted.id, fetched.id)
        assertEquals("Iberdrola", fetched.companyName)
        assertEquals("iberdrola", fetched.companyNameNormalized)
        assertEquals("3.0TD", fetched.tariffType)
        assertEquals(listOf(1.0, 2.0, 3.0, null, null, null), fetched.powerPrices)
        assertEquals(listOf(0.1, null, null, null, null, null), fetched.energyPrices)
        assertEquals(25.0, fetched.extraServices)
    }

    @Test
    fun `null periods and null extra services survive the round trip`() {
        repository.insert(
            collectedPrice(
                powerPrices = List(6) { null },
                energyPrices = List(6) { null },
                extraServices = null
            )
        )

        val fetched = repository.findPage(limit = 10, offset = 0).single()
        assertEquals(List(6) { null }, fetched.powerPrices)
        assertEquals(List(6) { null }, fetched.energyPrices)
        assertEquals(null, fetched.extraServices)
    }

    @Test
    fun `findPage returns most recently collected first`() {
        val base = Instant.parse("2026-01-01T00:00:00Z")
        repository.insert(collectedPrice(companyName = "Oldest", collectedAt = base))
        repository.insert(
            collectedPrice(companyName = "Newest", collectedAt = base.plusSeconds(120))
        )
        repository.insert(
            collectedPrice(companyName = "Middle", collectedAt = base.plusSeconds(60))
        )

        assertEquals(
            listOf("Newest", "Middle", "Oldest"),
            repository.findPage(limit = 10, offset = 0).map { row -> row.companyName }
        )
    }

    @Test
    fun `findPage honours limit and offset`() {
        val base = Instant.parse("2026-01-01T00:00:00Z")
        repeat(5) { index ->
            repository.insert(
                collectedPrice(
                    companyName = "Company $index",
                    collectedAt = base.plusSeconds(index.toLong())
                )
            )
        }

        val page = repository.findPage(limit = 2, offset = 2)
        assertEquals(2, page.size)
        assertEquals(listOf("Company 2", "Company 1"), page.map { row -> row.companyName })
    }

    @Test
    fun `count and findPage agree under the same filters`() {
        repository.insert(collectedPrice(companyName = "Iberdrola", tariffType = "3.0TD"))
        repository.insert(collectedPrice(companyName = "Endesa", tariffType = "3.0TD"))
        repository.insert(collectedPrice(companyName = "Endesa", tariffType = "3.1TD"))

        assertEquals(3L, repository.count())
        assertEquals(2L, repository.count(tariffType = "3.0TD"))
        assertEquals(2L, repository.count(companyNameNormalized = "endesa"))
        assertEquals(
            1L,
            repository.count(tariffType = "3.1TD", companyNameNormalized = "endesa")
        )
        assertEquals(
            1,
            repository.findPage(
                limit = 10,
                offset = 0,
                tariffType = "3.1TD",
                companyNameNormalized = "endesa"
            ).size
        )
    }
}
