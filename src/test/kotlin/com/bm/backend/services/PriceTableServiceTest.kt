package com.bm.backend.services

import com.bm.backend.database.DatabaseFactory
import com.bm.backend.models.BatchPriceTablesRequest
import com.bm.backend.models.IMPUESTO_ELECTRICO
import com.bm.backend.models.IVA
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PriceTableServiceTest {
    private lateinit var service: PriceTableService

    @BeforeEach
    fun setup() {
        DatabaseFactory.initTestDatabase()
        service = PriceTableService()
    }

    @AfterEach
    fun tearDown() {
        // Clean up test database
        java.io.File("test_price_tables.db").delete()
    }

    @Test
    fun `test service initialization`() {
        // Simple test to verify service can be created
        assertTrue(service is PriceTableService)
    }

    @Test
    fun `test getAllPriceTableResults returns empty initially`() {
        val result = service.getAllPriceTableResults()
        assertTrue(result.success)
        assertEquals(0, result.results.size)
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
