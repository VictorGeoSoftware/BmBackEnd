package com.bm.backend.database

import org.flywaydb.core.Flyway
import org.jetbrains.exposed.sql.Database
import org.slf4j.LoggerFactory
import javax.sql.DataSource

object DatabaseFactory {

    private val logger = LoggerFactory.getLogger(DatabaseFactory::class.java)

    fun init() {
        val dataSource = DataSourceFactory.create()
        runFlyway(dataSource)
        Database.connect(dataSource)
        logger.info("Database initialized (PostgreSQL via HikariCP)")
    }

    private fun runFlyway(dataSource: DataSource) {
        val flyway = Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration/postgres")
            .baselineOnMigrate(true)
            .load()
        val result = flyway.migrate()
        logger.info("Flyway: applied {} migration(s)", result.migrationsExecuted)
    }
}
