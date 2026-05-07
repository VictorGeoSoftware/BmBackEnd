package com.bm.backend.tools

import org.slf4j.LoggerFactory
import java.sql.Connection
import java.sql.DriverManager
import javax.sql.DataSource

/**
 * CLI tool to migrate data from a SQLite database to PostgreSQL.
 *
 * Copies all 9 tables in FK order, preserving IDs and encrypted PII bytes.
 * Runs inside a single Postgres transaction so it's all-or-nothing.
 *
 * Usage:
 *   java -cp app.jar com.bm.backend.tools.SqliteToPostgresMigrationKt \
 *       --sqlite-path price_tables.db \
 *       --pg-url jdbc:postgresql://localhost:5432/bm_backend \
 *       --pg-user bm_app \
 *       --pg-password secret
 *
 * Or via environment variables: SQLITE_PATH, DB_URL, DB_USER, DB_PASSWORD.
 */
fun main(args: Array<String>) {
    val params = parseArgs(args)
    val migration = SqliteToPostgresMigration(
        sqlitePath = params.sqlitePath,
        pgUrl = params.pgUrl,
        pgUser = params.pgUser,
        pgPassword = params.pgPassword
    )
    migration.run()
}

data class MigrationParams(
    val sqlitePath: String,
    val pgUrl: String,
    val pgUser: String,
    val pgPassword: String
)

private fun parseArgs(args: Array<String>): MigrationParams {
    val map = mutableMapOf<String, String>()
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--sqlite-path" -> { map["sqlitePath"] = args[++i] }
            "--pg-url"      -> { map["pgUrl"] = args[++i] }
            "--pg-user"     -> { map["pgUser"] = args[++i] }
            "--pg-password" -> { map["pgPassword"] = args[++i] }
        }
        i++
    }
    return MigrationParams(
        sqlitePath = map["sqlitePath"] ?: System.getenv("SQLITE_PATH") ?: "price_tables.db",
        pgUrl      = map["pgUrl"]      ?: System.getenv("DB_URL")      ?: error("DB_URL required"),
        pgUser     = map["pgUser"]     ?: System.getenv("DB_USER")     ?: error("DB_USER required"),
        pgPassword = map["pgPassword"] ?: System.getenv("DB_PASSWORD") ?: error("DB_PASSWORD required")
    )
}

/**
 * Stateless migration engine. Can also be called programmatically from tests
 * by passing a pre-built [DataSource] for Postgres.
 */
class SqliteToPostgresMigration(
    private val sqlitePath: String,
    private val pgUrl: String,
    private val pgUser: String,
    private val pgPassword: String
) {
    private val logger = LoggerFactory.getLogger(SqliteToPostgresMigration::class.java)

    /** Tables in FK-safe insertion order. */
    private val tables = listOf(
        TableSpec("price_table_results",  listOf("id", "file_name", "company_name")),
        TableSpec("tax_settings",         listOf("id", "iva", "impuesto_electrico")),
        TableSpec("termino_de_potencia",  listOf("id", "result_id", "titulo", "tabla_titulo")),
        TableSpec("termino_de_energia",   listOf("id", "result_id", "titulo", "tabla_base_titulo", "tabla_unica_titulo")),
        TableSpec("tarifas_potencia",     listOf("id", "termino_id", "tarifa", "potencia_contratada", "p1", "p2", "p3", "p4", "p5", "p6")),
        TableSpec("tarifas_energia_base", listOf("id", "termino_id", "tarifa", "potencia_contratada", "p1", "p2", "p3", "p4", "p5", "p6")),
        TableSpec("tarifas_energia_unica",listOf("id", "termino_id", "tarifa", "potencia_contratada", "p1", "p2", "p3", "p4", "p5", "p6")),
        TableSpec("user_data",            listOf("id", "uid", "email", "display_name", "photo_url", "provider_ids", "token_issued_at", "token_expires_at", "last_login_at", "created_at", "updated_at")),
        TableSpec("user_activity",        listOf("id", "email", "name", "is_online", "monthly_usage_count", "month_key", "usage_started_at", "first_connected_at", "last_connected_at", "last_disconnected_at", "updated_at"))
    )

    fun run(): Map<String, Int> {
        val sqliteUrl = "jdbc:sqlite:$sqlitePath"
        logger.info("Starting migration: {} -> {}", sqliteUrl, pgUrl)

        val counts = mutableMapOf<String, Int>()

        DriverManager.getConnection(sqliteUrl).use { sqlite ->
            DriverManager.getConnection(pgUrl, pgUser, pgPassword).use { pg ->
                pg.autoCommit = false
                try {
                    for (table in tables) {
                        val n = copyTable(sqlite, pg, table)
                        counts[table.name] = n
                        logger.info("  {} — {} rows copied", table.name, n)
                    }
                    // Reset Postgres sequences to max(id)+1 so future inserts don't collide
                    resetSequences(pg)
                    pg.commit()
                    logger.info("Migration committed successfully: {}", counts)
                } catch (e: Exception) {
                    pg.rollback()
                    logger.error("Migration failed, rolled back", e)
                    throw e
                }
            }
        }
        return counts
    }

    private fun copyTable(sqlite: Connection, pg: Connection, table: TableSpec): Int {
        val columns = table.columns.joinToString(", ")
        val placeholders = table.columns.joinToString(", ") { "?" }
        val insertSql = "INSERT INTO ${table.name} ($columns) VALUES ($placeholders)"

        val selectSql = "SELECT $columns FROM ${table.name}"
        var count = 0

        sqlite.createStatement().use { stmt ->
            stmt.executeQuery(selectSql).use { rs ->
                pg.prepareStatement(insertSql).use { ps ->
                    while (rs.next()) {
                        for ((idx, col) in table.columns.withIndex()) {
                            val value = rs.getObject(col)
                            // SQLite stores booleans as 0/1 integers
                            if (col == "is_online" && value is Number) {
                                ps.setBoolean(idx + 1, value.toInt() != 0)
                            } else {
                                ps.setObject(idx + 1, value)
                            }
                        }
                        ps.addBatch()
                        count++
                        if (count % 1000 == 0) ps.executeBatch()
                    }
                    if (count % 1000 != 0) ps.executeBatch()
                }
            }
        }
        return count
    }

    private fun resetSequences(pg: Connection) {
        // Postgres SERIAL columns use sequences named <table>_id_seq
        val tablesWithSerial = tables.map { it.name }
        pg.createStatement().use { stmt ->
            for (table in tablesWithSerial) {
                val seqName = "${table}_id_seq"
                stmt.execute(
                    """
                    SELECT setval('$seqName',
                        COALESCE((SELECT MAX(id) FROM $table), 0) + 1,
                        false)
                    """.trimIndent()
                )
            }
        }
    }

    private data class TableSpec(val name: String, val columns: List<String>)
}
