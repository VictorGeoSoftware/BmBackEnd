package com.bm.backend.testing

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.sql.Database
import org.testcontainers.containers.PostgreSQLContainer
import javax.sql.DataSource

/**
 * Shared Postgres Testcontainer setup.
 * Lazily starts a single Postgres 16 container for the entire test JVM.
 * Runs Flyway migrations on first access.
 *
 * Usage: call [ensureStarted] in `@BeforeAll` or `initDatabase()`.
 */
object PostgresTestSetup {

    private var started = false
    lateinit var dataSource: DataSource
        private set
    lateinit var database: Database
        private set

    private val container = PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("bm_test")
        .withUsername("test")
        .withPassword("test")

    @Synchronized
    fun ensureStarted() {
        if (started) return
        container.start()

        val config = HikariConfig().apply {
            jdbcUrl = container.jdbcUrl
            username = container.username
            password = container.password
            driverClassName = "org.postgresql.Driver"
            maximumPoolSize = 4
            poolName = "bm-postgres-test-pool"
        }
        dataSource = HikariDataSource(config)

        Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration/postgres")
            .cleanDisabled(false)
            .load()
            .also { it.clean() }
            .also { it.migrate() }

        database = Database.connect(dataSource)
        started = true
    }

    /**
     * Re-runs Flyway clean+migrate to reset all tables.
     * Call this from `@BeforeEach` to isolate tests.
     */
    fun resetSchema() {
        Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration/postgres")
            .cleanDisabled(false)
            .load()
            .also { it.clean() }
            .also { it.migrate() }
    }
}
