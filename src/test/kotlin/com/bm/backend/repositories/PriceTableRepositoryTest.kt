package com.bm.backend.repositories

import com.bm.backend.database.DatabaseFactory

/** SQLite-backed contract tests for PriceTableRepository. */
class PriceTableRepositoryTest : AbstractPriceTableRepositoryTest() {
    override fun initDatabase() {
        DatabaseFactory.initTestDatabase()
    }
}
