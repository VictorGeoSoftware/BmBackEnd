package com.bm.backend.testing

import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.BeforeAll

/**
 * Base class for integration tests against Postgres via Testcontainers.
 * Skips gracefully when Docker is unavailable.
 */
abstract class PostgresIntegrationTest {

    companion object {
        @BeforeAll
        @JvmStatic
        fun initPostgres() {
            Assumptions.assumeTrue(
                DockerAvailable.check(),
                "Docker is not available — skipping Postgres integration tests"
            )
            PostgresTestSetup.ensureStarted()
        }
    }
}
