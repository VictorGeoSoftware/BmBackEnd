package com.bm.backend.database

import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.File

object DatabaseFactory {
    fun init() {
        val database = Database.connect(
            url = "jdbc:sqlite:price_tables.db",
            driver = "org.sqlite.JDBC"
        )

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

    fun initTestDatabase() {
        val testDbFile = File("test_price_tables.db")
        if (testDbFile.exists()) {
            testDbFile.delete()
        }

        val database = Database.connect(
            url = "jdbc:sqlite:test_price_tables.db",
            driver = "org.sqlite.JDBC"
        )

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
}
