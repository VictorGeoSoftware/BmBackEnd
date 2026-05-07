package com.bm.backend.repositories.postgres

import com.bm.backend.repositories.AbstractUserActivityRepositoryTest
import com.bm.backend.testing.DockerAvailable
import com.bm.backend.testing.PostgresTestSetup
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.BeforeAll

/** Postgres-backed contract tests for UserActivityRepository. */
class PgUserActivityRepositoryTest : AbstractUserActivityRepositoryTest() {

    companion object {
        @BeforeAll
        @JvmStatic
        fun startPostgres() {
            Assumptions.assumeTrue(DockerAvailable.check(), "Docker not available")
            PostgresTestSetup.ensureStarted()
        }
    }

    override fun initDatabase() {
        PostgresTestSetup.database
    }
}
