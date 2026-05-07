package com.bm.backend.repositories

import com.bm.backend.database.DatabaseFactory

/** SQLite-backed contract tests for UserActivityRepository. */
class UserActivityRepositoryTest : AbstractUserActivityRepositoryTest() {
    override fun initDatabase() {
        DatabaseFactory.initTestDatabase()
    }
}
