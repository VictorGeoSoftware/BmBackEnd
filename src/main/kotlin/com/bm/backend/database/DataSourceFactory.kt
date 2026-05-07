package com.bm.backend.database

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.slf4j.LoggerFactory
import javax.sql.DataSource

/**
 * Creates a [DataSource] (HikariCP pool) based on the configured DB vendor.
 *
 * Supported vendors:
 * - **sqlite** — single-connection pool over the SQLite JDBC driver.
 * - **postgres** — sized pool over the PostgreSQL JDBC driver; requires
 *   `DB_URL`, `DB_USER`, and `DB_PASSWORD` environment variables.
 */
object DataSourceFactory {

    private val logger = LoggerFactory.getLogger(DataSourceFactory::class.java)

    /** Recognized vendor identifiers (case-insensitive). */
    enum class Vendor { SQLITE, POSTGRES }

    /**
     * Resolves [Vendor] from the `DB_VENDOR` environment variable.
     * Defaults to [Vendor.SQLITE] when the variable is absent or blank.
     */
    fun resolveVendor(): Vendor {
        val raw = System.getenv("DB_VENDOR")?.trim()?.lowercase() ?: "sqlite"
        return when (raw) {
            "sqlite" -> Vendor.SQLITE
            "postgres", "postgresql" -> Vendor.POSTGRES
            else -> throw IllegalArgumentException(
                "Unsupported DB_VENDOR '$raw'. Supported values: sqlite, postgres"
            )
        }
    }

    /**
     * Builds a [DataSource] for the given [vendor].
     *
     * For **Postgres** the following env vars are **required** (app will fail-fast if missing):
     * `DB_URL`, `DB_USER`, `DB_PASSWORD`.
     */
    fun create(vendor: Vendor = resolveVendor()): DataSource {
        return when (vendor) {
            Vendor.SQLITE -> createSqliteDataSource()
            Vendor.POSTGRES -> createPostgresDataSource()
        }
    }

    // ── SQLite ──────────────────────────────────────────────────────

    private fun createSqliteDataSource(): DataSource {
        val dbUrl = System.getenv("DB_URL") ?: "jdbc:sqlite:price_tables.db"
        val config = HikariConfig().apply {
            jdbcUrl = dbUrl
            driverClassName = "org.sqlite.JDBC"
            // SQLite supports only a single writer; keep pool at 1 to avoid locking errors.
            maximumPoolSize = 1
            // Disable idle timeout — the single connection should stay alive.
            idleTimeout = 0
            poolName = "bm-sqlite-pool"
        }
        logger.info("Creating SQLite DataSource at: {}", dbUrl)
        return HikariDataSource(config)
    }

    // ── PostgreSQL ──────────────────────────────────────────────────

    private fun createPostgresDataSource(): DataSource {
        val dbUrl = requireEnv("DB_URL")
        val dbUser = requireEnv("DB_USER")
        val dbPassword = requireEnv("DB_PASSWORD")

        val config = HikariConfig().apply {
            jdbcUrl = dbUrl
            username = dbUser
            password = dbPassword
            driverClassName = "org.postgresql.Driver"
            maximumPoolSize = (Runtime.getRuntime().availableProcessors() * 2).coerceIn(4, 20)
            minimumIdle = 2
            idleTimeout = 600_000      // 10 min
            connectionTimeout = 30_000 // 30 s
            maxLifetime = 1_800_000    // 30 min
            poolName = "bm-postgres-pool"
            // Enforce TLS unless explicitly overridden in the URL
            if (!dbUrl.contains("sslmode")) {
                addDataSourceProperty("sslmode", "require")
            }
        }
        logger.info("Creating Postgres DataSource at: {} (pool size {})", dbUrl, config.maximumPoolSize)
        return HikariDataSource(config)
    }

    // ── Test helpers ────────────────────────────────────────────────

    /**
     * Creates a single-connection SQLite pool pointed at a test DB file.
     * Intended for characterization / integration tests that still run on SQLite.
     */
    fun createTestSqliteDataSource(url: String = "jdbc:sqlite:test_price_tables.db"): DataSource {
        val config = HikariConfig().apply {
            jdbcUrl = url
            driverClassName = "org.sqlite.JDBC"
            maximumPoolSize = 1
            idleTimeout = 0
            poolName = "bm-sqlite-test-pool"
        }
        return HikariDataSource(config)
    }

    // ── Internal ────────────────────────────────────────────────────

    private fun requireEnv(name: String): String {
        return System.getenv(name)
            ?: throw IllegalStateException(
                "Required environment variable '$name' is not set. " +
                        "Cannot start with DB_VENDOR=postgres without it."
            )
    }
}
