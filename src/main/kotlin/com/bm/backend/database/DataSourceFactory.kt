package com.bm.backend.database

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.slf4j.LoggerFactory
import javax.sql.DataSource

/**
 * Creates a PostgreSQL [DataSource] backed by a HikariCP connection pool.
 *
 * Required environment variables: `DB_URL`, `DB_USER`, `DB_PASSWORD`.
 * The app will fail fast on startup if any are missing.
 */
object DataSourceFactory {

    private val logger = LoggerFactory.getLogger(DataSourceFactory::class.java)

    /**
     * Builds the production [DataSource] from environment variables.
     */
    fun create(): DataSource {
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

    // ── Internal ────────────────────────────────────────────────────

    private fun requireEnv(name: String): String {
        return System.getenv(name)
            ?: throw IllegalStateException(
                "Required environment variable '$name' is not set. " +
                        "Cannot start without database credentials."
            )
    }
}
