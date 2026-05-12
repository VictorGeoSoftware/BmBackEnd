package com.bm.backend.tools

import com.bm.backend.testing.DockerAvailable
import com.bm.backend.testing.PostgresTestSetup
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.io.File
import java.sql.DriverManager
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Integration test for the SQLite→Postgres migration tool.
 * Seeds a fixture SQLite DB, runs the migration, and verifies row counts + content.
 */
class SqliteToPostgresMigrationTest {

    companion object {
        private const val FIXTURE_DB = "test_migration_source.db"

        @BeforeAll
        @JvmStatic
        fun setup() {
            Assumptions.assumeTrue(DockerAvailable.check(), "Docker not available")
            PostgresTestSetup.ensureStarted()
            seedFixtureSqlite()
        }

        private fun seedFixtureSqlite() {
            val file = File(FIXTURE_DB)
            if (file.exists()) file.delete()

            DriverManager.getConnection("jdbc:sqlite:$FIXTURE_DB").use { conn ->
                conn.createStatement().use { stmt ->
                    // Apply the SQLite V1 schema inline
                    stmt.execute("""
                        CREATE TABLE price_table_results (
                            id INTEGER PRIMARY KEY AUTOINCREMENT,
                            file_name VARCHAR(255) NOT NULL,
                            company_name VARCHAR(255) NOT NULL
                        )
                    """)
                    stmt.execute("""
                        CREATE TABLE termino_de_potencia (
                            id INTEGER PRIMARY KEY AUTOINCREMENT,
                            result_id INTEGER NOT NULL REFERENCES price_table_results(id),
                            titulo TEXT NOT NULL,
                            tabla_titulo TEXT NOT NULL
                        )
                    """)
                    stmt.execute("""
                        CREATE TABLE termino_de_energia (
                            id INTEGER PRIMARY KEY AUTOINCREMENT,
                            result_id INTEGER NOT NULL REFERENCES price_table_results(id),
                            titulo TEXT NOT NULL,
                            tabla_base_titulo TEXT NOT NULL,
                            tabla_unica_titulo TEXT NOT NULL
                        )
                    """)
                    stmt.execute("""
                        CREATE TABLE tarifas_potencia (
                            id INTEGER PRIMARY KEY AUTOINCREMENT,
                            termino_id INTEGER NOT NULL,
                            tarifa VARCHAR(50) NOT NULL,
                            potencia_contratada VARCHAR(100),
                            p1 DOUBLE, p2 DOUBLE, p3 DOUBLE, p4 DOUBLE, p5 DOUBLE, p6 DOUBLE
                        )
                    """)
                    stmt.execute("""
                        CREATE TABLE tarifas_energia_base (
                            id INTEGER PRIMARY KEY AUTOINCREMENT,
                            termino_id INTEGER NOT NULL,
                            tarifa VARCHAR(50) NOT NULL,
                            potencia_contratada VARCHAR(100),
                            p1 DOUBLE, p2 DOUBLE, p3 DOUBLE, p4 DOUBLE, p5 DOUBLE, p6 DOUBLE
                        )
                    """)
                    stmt.execute("""
                        CREATE TABLE tarifas_energia_unica (
                            id INTEGER PRIMARY KEY AUTOINCREMENT,
                            termino_id INTEGER NOT NULL,
                            tarifa VARCHAR(50) NOT NULL,
                            potencia_contratada VARCHAR(100),
                            p1 DOUBLE, p2 DOUBLE, p3 DOUBLE, p4 DOUBLE, p5 DOUBLE, p6 DOUBLE
                        )
                    """)
                    stmt.execute("""
                        CREATE TABLE tax_settings (
                            id INTEGER PRIMARY KEY AUTOINCREMENT,
                            iva DOUBLE NOT NULL,
                            impuesto_electrico DOUBLE NOT NULL
                        )
                    """)
                    stmt.execute("""
                        CREATE TABLE user_data (
                            id INTEGER PRIMARY KEY AUTOINCREMENT,
                            uid VARCHAR(128) NOT NULL,
                            email VARCHAR(255),
                            display_name VARCHAR(255),
                            photo_url TEXT,
                            provider_ids TEXT NOT NULL,
                            token_issued_at BIGINT NOT NULL,
                            token_expires_at BIGINT NOT NULL,
                            last_login_at BIGINT NOT NULL,
                            created_at BIGINT NOT NULL,
                            updated_at BIGINT NOT NULL
                        )
                    """)
                    stmt.execute("""
                        CREATE TABLE user_activity (
                            id INTEGER PRIMARY KEY AUTOINCREMENT,
                            email VARCHAR(255) NOT NULL,
                            name VARCHAR(255) NOT NULL,
                            is_online BOOLEAN NOT NULL,
                            monthly_usage_count INTEGER NOT NULL,
                            month_key VARCHAR(7) NOT NULL,
                            usage_started_at BIGINT,
                            first_connected_at BIGINT,
                            last_connected_at BIGINT,
                            last_disconnected_at BIGINT,
                            updated_at BIGINT NOT NULL
                        )
                    """)

                    // Seed data
                    stmt.execute("INSERT INTO price_table_results (id, file_name, company_name) VALUES (1, 'iberdrola.pdf', 'Iberdrola')")
                    stmt.execute("INSERT INTO price_table_results (id, file_name, company_name) VALUES (2, 'endesa.pdf', 'Endesa')")
                    stmt.execute("INSERT INTO termino_de_potencia (id, result_id, titulo, tabla_titulo) VALUES (1, 1, 'Potencia', 'Precio Potencia')")
                    stmt.execute("INSERT INTO termino_de_energia (id, result_id, titulo, tabla_base_titulo, tabla_unica_titulo) VALUES (1, 1, 'Energia', 'Base', 'Unica')")
                    stmt.execute("INSERT INTO tarifas_potencia (id, termino_id, tarifa, p1, p2) VALUES (1, 1, '2.0TD', 0.05, 0.03)")
                    stmt.execute("INSERT INTO tarifas_energia_base (id, termino_id, tarifa, p1) VALUES (1, 1, '2.0TD', 0.18)")
                    stmt.execute("INSERT INTO tarifas_energia_unica (id, termino_id, tarifa, p1) VALUES (1, 1, '2.0TD', 0.15)")
                    stmt.execute("INSERT INTO tax_settings (id, iva, impuesto_electrico) VALUES (1, 21.0, 5.11)")
                    stmt.execute("INSERT INTO user_data (id, uid, email, display_name, photo_url, provider_ids, token_issued_at, token_expires_at, last_login_at, created_at, updated_at) VALUES (1, 'uid-1', 'ENC_alice', 'ENC_Alice', NULL, 'google.com', 1000, 2000, 900, 800, 1000)")
                    stmt.execute("INSERT INTO user_activity (id, email, name, is_online, monthly_usage_count, month_key, usage_started_at, last_connected_at, last_disconnected_at, updated_at) VALUES (1, 'alice@example.com', 'Alice', 1, 5, '2025-05', 1000, 2000, 1500, 2000)")
                }
            }
        }
    }

    @Test
    fun `migration copies all rows and preserves IDs`() {
        // Clean Postgres tables first
        val ds = PostgresTestSetup.dataSource
        Flyway.configure()
            .dataSource(ds)
            .locations("classpath:db/migration/postgres")
            .cleanDisabled(false)
            .load()
            .also { it.clean() }
            .also { it.migrate() }

        val pgConn = ds.connection
        val migration = SqliteToPostgresMigration(
            sqlitePath = FIXTURE_DB,
            pgUrl = pgConn.metaData.url,
            pgUser = "test",
            pgPassword = "test"
        )
        pgConn.close()

        val counts = migration.run()

        assertEquals(2, counts["price_table_results"])
        assertEquals(1, counts["termino_de_potencia"])
        assertEquals(1, counts["termino_de_energia"])
        assertEquals(1, counts["tarifas_potencia"])
        assertEquals(1, counts["tarifas_energia_base"])
        assertEquals(1, counts["tarifas_energia_unica"])
        assertEquals(1, counts["tax_settings"])
        assertEquals(1, counts["user_data"])
        assertEquals(1, counts["user_activity"])

        // Verify content preservation
        ds.connection.use { conn ->
            conn.createStatement().use { stmt ->
                // Check IDs preserved
                val rs = stmt.executeQuery("SELECT id, file_name FROM price_table_results ORDER BY id")
                assertTrue(rs.next()); assertEquals(1, rs.getInt("id")); assertEquals("iberdrola.pdf", rs.getString("file_name"))
                assertTrue(rs.next()); assertEquals(2, rs.getInt("id")); assertEquals("endesa.pdf", rs.getString("file_name"))

                // Check encrypted PII bytes preserved as-is
                val ud = stmt.executeQuery("SELECT email FROM user_data WHERE uid = 'uid-1'")
                assertTrue(ud.next())
                assertEquals("ENC_alice", ud.getString("email"))

                // Check boolean conversion
                val ua = stmt.executeQuery("SELECT is_online FROM user_activity WHERE email = 'alice@example.com'")
                assertTrue(ua.next())
                assertEquals(true, ua.getBoolean("is_online"))
            }
        }

        // Cleanup
        File(FIXTURE_DB).delete()
    }
}
