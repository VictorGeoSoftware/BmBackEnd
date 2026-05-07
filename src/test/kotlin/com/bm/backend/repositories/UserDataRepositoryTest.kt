package com.bm.backend.repositories

import com.bm.backend.database.DatabaseFactory

/** SQLite-backed contract tests for UserDataRepository. */
class UserDataRepositoryTest : AbstractUserDataRepositoryTest() {
    override fun initDatabase() {
        DatabaseFactory.initTestDatabase()
    }
}
