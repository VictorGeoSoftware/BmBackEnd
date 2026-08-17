package com.bm.backend.repositories

import com.bm.backend.testing.DockerAvailable
import com.bm.backend.testing.PostgresTestSetup
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Smoke test proving the Testcontainers + Flyway + Exposed harness works.
 */
class PostgresSmokeTest {

    companion object {
        @BeforeAll
        @JvmStatic
        fun startPostgres() {
            Assumptions.assumeTrue(DockerAvailable.check(), "Docker not available")
            PostgresTestSetup.ensureStarted()
        }
    }

    @Test
    fun `SELECT 1 returns 1`() {
        val result = transaction(PostgresTestSetup.database) {
            exec("SELECT 1 AS n") { rs ->
                rs.next()
                rs.getInt("n")
            }
        }
        assertEquals(1, result)
    }

    @Test
    fun `Flyway created all tables`() {
        val tables = transaction(PostgresTestSetup.database) {
            val found = mutableListOf<String>()
            exec(
                """
                SELECT table_name FROM information_schema.tables
                WHERE table_schema = 'public' AND table_type = 'BASE TABLE'
                ORDER BY table_name
                """.trimIndent()
            ) { rs ->
                while (rs.next()) found.add(rs.getString("table_name"))
            }
            found
        }!!
        val expected = listOf(
            "admin_users",
            "collected_prices",
            "granted_users",
            "price_table_results",
            "tarifas_energia_base", "tarifas_energia_unica", "tarifas_potencia",
            "tax_settings",
            "termino_de_energia", "termino_de_potencia",
            "user_activity", "user_consumption", "user_data"
        )
        assertEquals(expected, tables.filter { !it.startsWith("flyway_") }.sorted())
    }
}
