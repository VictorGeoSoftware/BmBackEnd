package com.bm.backend.services

import com.bm.backend.database.DatabaseFactory
import com.bm.backend.models.ExtractedTables
import com.bm.backend.models.StorePriceTablesRequest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
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
    fun `storePriceTables should store valid data successfully`() {
        val request = StorePriceTablesRequest(
            filename = "test.pdf",
            extracted_tables = ExtractedTables(
                termino_potencia = buildJsonObject { put("key", "value") },
                termino_energia_clasica_base = buildJsonObject { put("data", "sample") }
            ),
            source = "test-source",
            timestamp = "2024-01-01T00:00:00Z"
        )

        val response = service.storePriceTables(request)

        assertTrue(response.success)
        assertEquals("Data stored successfully", response.message)
        assertTrue(response.record_id > 0)
    }

    @Test
    fun `storePriceTables should throw validation exception for blank filename`() {
        val request = StorePriceTablesRequest(
            filename = "",
            extracted_tables = ExtractedTables(
                termino_potencia = buildJsonObject { put("key", "value") }
            ),
            source = "test-source"
        )

        assertThrows<ValidationException> {
            service.storePriceTables(request)
        }
    }

    @Test
    fun `storePriceTables should throw validation exception for blank source`() {
        val request = StorePriceTablesRequest(
            filename = "test.pdf",
            extracted_tables = ExtractedTables(
                termino_potencia = buildJsonObject { put("key", "value") }
            ),
            source = ""
        )

        assertThrows<ValidationException> {
            service.storePriceTables(request)
        }
    }

    @Test
    fun `storePriceTables should throw validation exception for empty extracted tables`() {
        val request = StorePriceTablesRequest(
            filename = "test.pdf",
            extracted_tables = ExtractedTables(),
            source = "test-source"
        )

        assertThrows<ValidationException> {
            service.storePriceTables(request)
        }
    }

    @Test
    fun `getAllPriceTables should return stored data`() {
        val request = StorePriceTablesRequest(
            filename = "test.pdf",
            extracted_tables = ExtractedTables(
                termino_potencia = buildJsonObject { put("key", "value") }
            ),
            source = "test-source"
        )

        service.storePriceTables(request)
        val response = service.getAllPriceTables()

        assertTrue(response.success)
        assertEquals(1, response.data.size)
        assertEquals(1, response.total)
    }

    @Test
    fun `getPriceTableById should return specific record`() {
        val request = StorePriceTablesRequest(
            filename = "test.pdf",
            extracted_tables = ExtractedTables(
                termino_potencia = buildJsonObject { put("key", "value") }
            ),
            source = "test-source"
        )

        val storeResponse = service.storePriceTables(request)
        val getResponse = service.getPriceTableById(storeResponse.record_id)

        assertNotNull(getResponse)
        assertTrue(getResponse.success)
        assertEquals("test.pdf", getResponse.data.filename)
    }

    @Test
    fun `getPriceTableById should return null for non-existent record`() {
        val response = service.getPriceTableById(999)
        assertEquals(null, response)
    }
}
