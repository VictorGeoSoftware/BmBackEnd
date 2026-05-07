package com.bm.backend.testing

/**
 * JUnit condition: returns true when Docker is reachable.
 * Used by [PostgresIntegrationTest] to skip Postgres tests
 * in environments without Docker.
 */
object DockerAvailable {
    fun check(): Boolean {
        return try {
            val process = ProcessBuilder("docker", "info")
                .redirectErrorStream(true)
                .start()
            val exitCode = process.waitFor()
            exitCode == 0
        } catch (_: Throwable) {
            false
        }
    }
}
