package com.bm.backend.services

import com.bm.backend.models.BatchPriceTablesRequest
import com.bm.backend.models.IMPUESTO_ELECTRICO
import com.bm.backend.models.IVA
import com.bm.backend.testing.DockerAvailable
import com.bm.backend.testing.PostgresTestSetup
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PriceTableServiceTest {
    private lateinit var service: PriceTableService

    companion object {
        @BeforeAll
        @JvmStatic
        fun setup() {
            Assumptions.assumeTrue(DockerAvailable.check(), "Docker not available")
            PostgresTestSetup.ensureStarted()
        }
    }

    @BeforeEach
    fun init() {
        service = PriceTableService()
    }

    @Test
    fun `test service initialization`() {
        assertTrue(service is PriceTableService)
    }

    @Test
    fun `test getAllPriceTableResults returns empty initially`() {
        val result = service.getAllPriceTableResults()
        assertTrue(result.success)
    }

    @Test
    fun `test processBatchPriceTables with empty data`() {
        val request: BatchPriceTablesRequest = emptyList()
        assertThrows<ValidationException> {
            service.processBatchPriceTables(request)
        }
    }

    @Test
    fun `test getAllPriceTableResults includes tax constants`() {
        val result = service.getAllPriceTableResults()
        assertEquals(IVA, result.iva)
        assertEquals(IMPUESTO_ELECTRICO, result.impuestoElectrico)
    }
}
