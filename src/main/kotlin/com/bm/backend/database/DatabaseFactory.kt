package com.bm.backend.database

import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

object DatabaseFactory {

    private val logger = LoggerFactory.getLogger(DatabaseFactory::class.java)
    private const val DEFAULT_DB_URL = "jdbc:sqlite:price_tables.db"

    fun init() {
        val dbUrl = System.getenv("DB_URL") ?: DEFAULT_DB_URL
        val dbPath = dbUrl.removePrefix("jdbc:sqlite:")

        val database = Database.connect(
            url = dbUrl,
            driver = "org.sqlite.JDBC"
        )

        applySecurityPragmas(database)
        restrictFilePermissions(dbPath)

        transaction(database) {
            SchemaUtils.create(
                PriceTableResultsDb,
                TerminoDePotenciaDb,
                TerminoDeEnergiaDb,
                TarifasPotenciaDb,
                TarifasEnergiaBaseDb,
                TarifasEnergiaUnicaDb,
                UserDataDb
            )
        }

        logger.info("Database initialized at: {}", dbPath)
    }

    fun initTestDatabase() {
        val testDbFile = File("test_price_tables.db")
        if (testDbFile.exists()) {
            testDbFile.delete()
        }

        val database = Database.connect(
            url = "jdbc:sqlite:test_price_tables.db",
            driver = "org.sqlite.JDBC"
        )

        applySecurityPragmas(database)

        transaction(database) {
            SchemaUtils.create(
                PriceTableResultsDb,
                TerminoDePotenciaDb,
                TerminoDeEnergiaDb,
                TarifasPotenciaDb,
                TarifasEnergiaBaseDb,
                TarifasEnergiaUnicaDb,
                UserDataDb
            )
        }
    }

    private fun applySecurityPragmas(database: Database) {
        // PRAGMAs must be applied via raw JDBC — Exposed's exec() uses executeQuery
        // which fails for PRAGMA statements that don't return a result set
        val url = database.url
        java.sql.DriverManager.getConnection(url).use { conn ->
            conn.createStatement().use { stmt ->
                // Enable WAL mode for better concurrent access and crash recovery
                stmt.execute("PRAGMA journal_mode=WAL;")
                // Enforce foreign key constraints
                stmt.execute("PRAGMA foreign_keys=ON;")
                // Overwrite deleted data with zeros to prevent residual data recovery
                stmt.execute("PRAGMA secure_delete=ON;")
                // Sync data to disk more aggressively to prevent corruption
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
                // Owner read/write only (600)
                val perms = PosixFilePermissions.fromString("rw-------")
                Files.setPosixFilePermissions(file.toPath(), perms)

                // Also restrict WAL and SHM journal files
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
