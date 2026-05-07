package com.bm.backend.database

import org.flywaydb.core.Flyway
import org.jetbrains.exposed.sql.Database
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import javax.sql.DataSource

object DatabaseFactory {

    private val logger = LoggerFactory.getLogger(DatabaseFactory::class.java)

    fun init() {
        val vendor = DataSourceFactory.resolveVendor()
        val dataSource = DataSourceFactory.create(vendor)

        when (vendor) {
            DataSourceFactory.Vendor.SQLITE -> {
                val dbUrl = System.getenv("DB_URL") ?: "jdbc:sqlite:price_tables.db"
                val dbPath = dbUrl.removePrefix("jdbc:sqlite:")
                applySecurityPragmas(dataSource)
                restrictFilePermissions(dbPath)
            }
            DataSourceFactory.Vendor.POSTGRES -> {
                logger.info("Connected to PostgreSQL via HikariCP")
            }
        }

        runFlyway(dataSource, vendor)
        Database.connect(dataSource)

        logger.info("Database initialized (vendor={})", vendor)
    }

    /**
     * Test-only initializer. Uses Flyway on a fresh SQLite file so the schema
     * is created through the same path as production.
     */
    fun initTestDatabase() {
        val testDbFile = File("test_price_tables.db")
        if (testDbFile.exists()) {
            testDbFile.delete()
        }

        val dataSource = DataSourceFactory.createTestSqliteDataSource()
        applySecurityPragmas(dataSource)
        runFlyway(dataSource, DataSourceFactory.Vendor.SQLITE)
        Database.connect(dataSource)
    }

    // ── Flyway ──────────────────────────────────────────────────────

    private fun runFlyway(dataSource: DataSource, vendor: DataSourceFactory.Vendor) {
        val locations = when (vendor) {
            DataSourceFactory.Vendor.SQLITE -> "classpath:db/migration/sqlite"
            DataSourceFactory.Vendor.POSTGRES -> "classpath:db/migration/postgres"
        }
        val flyway = Flyway.configure()
            .dataSource(dataSource)
            .locations(locations)
            .baselineOnMigrate(true)   // tolerate existing non-empty DBs on first Flyway run
            .load()
        val result = flyway.migrate()
        logger.info("Flyway: applied {} migration(s)", result.migrationsExecuted)
    }

    // ── SQLite-specific helpers ─────────────────────────────────────

    private fun applySecurityPragmas(dataSource: DataSource) {
        dataSource.connection.use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("PRAGMA journal_mode=WAL;")
                stmt.execute("PRAGMA foreign_keys=ON;")
                stmt.execute("PRAGMA secure_delete=ON;")
                stmt.execute("PRAGMA synchronous=FULL;")
            }
        }
        logger.info("SQLite security PRAGMAs applied (WAL + foreign_keys + secure_delete + synchronous=FULL)")
    }

    private fun restrictFilePermissions(dbPath: String) {
        try {
            val file = File(dbPath)
            if (!file.exists()) return

            val os = System.getProperty("os.name").lowercase()
            if (os.contains("nix") || os.contains("nux") || os.contains("mac")) {
                val perms = PosixFilePermissions.fromString("rw-------")
                Files.setPosixFilePermissions(file.toPath(), perms)

                val walFile = File("$dbPath-wal")
                val shmFile = File("$dbPath-shm")
                if (walFile.exists()) Files.setPosixFilePermissions(walFile.toPath(), perms)
                if (shmFile.exists()) Files.setPosixFilePermissions(shmFile.toPath(), perms)

                logger.info("Database file permissions restricted to owner-only (600)")
            }
        } catch (e: Exception) {
            logger.warn("Could not set file permissions on database: ${e.message}")
        }
    }
}
