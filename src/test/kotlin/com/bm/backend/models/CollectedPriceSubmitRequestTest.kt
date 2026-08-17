package com.bm.backend.models

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Structural validation of the collected-prices submission DTO. */
class CollectedPriceSubmitRequestTest {

    private fun request(
        companyName: String? = "Iberdrola",
        tariffType: String? = "3.0TD",
        powerPrices: Map<String, Double>? = mapOf("P1" to 1.0),
        energyPrices: Map<String, Double>? = mapOf("P1" to 0.1),
        extraServices: Double? = null
    ) = CollectedPriceSubmitRequest(
        companyName = companyName,
        tariffType = tariffType,
        powerPrices = powerPrices,
        energyPrices = energyPrices,
        extraServices = extraServices
    )

    @Test
    fun `toDomainModel maps a well formed request`() {
        val submission = request(extraServices = 12.0).toDomainModel()

        assertEquals("Iberdrola", submission.companyName)
        assertEquals("3.0TD", submission.tariffType)
        assertEquals(mapOf("P1" to 1.0), submission.powerPricesByPeriod)
        assertEquals(12.0, submission.extraServices)
    }

    @Test
    fun `toDomainModel rejects a missing company name`() {
        assertFailsWith<IllegalArgumentException> { request(companyName = null).toDomainModel() }
        assertFailsWith<IllegalArgumentException> { request(companyName = "   ").toDomainModel() }
    }

    @Test
    fun `toDomainModel rejects a missing tariff type`() {
        assertFailsWith<IllegalArgumentException> { request(tariffType = null).toDomainModel() }
        assertFailsWith<IllegalArgumentException> { request(tariffType = " ").toDomainModel() }
    }

    @Test
    fun `toDomainModel rejects a submission with no prices at all`() {
        assertFailsWith<IllegalArgumentException> {
            request(powerPrices = null, energyPrices = null).toDomainModel()
        }
    }

    @Test
    fun `toDomainModel accepts power only or energy only submissions`() {
        request(energyPrices = emptyMap()).toDomainModel()
        request(powerPrices = emptyMap()).toDomainModel()
    }

    @Test
    fun `toDomainModel rejects period keys outside P1 to P6`() {
        val error = assertFailsWith<IllegalArgumentException> {
            request(powerPrices = mapOf("P7" to 1.0, "X" to 2.0)).toDomainModel()
        }
        assertEquals(true, error.message?.contains("expected P1..P6"))
    }
}
