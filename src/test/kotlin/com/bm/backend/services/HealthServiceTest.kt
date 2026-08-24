package com.bm.backend.services

import com.bm.backend.testing.FakeDatabaseHealth
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HealthServiceTest {

    @Test
    fun `is ready when the database is reachable`() {
        val service = HealthService(FakeDatabaseHealth(reachable = true))
        assertTrue(service.isReady())
    }

    @Test
    fun `is not ready when the database is unreachable`() {
        val service = HealthService(FakeDatabaseHealth(reachable = false))
        assertFalse(service.isReady())
    }

    @Test
    fun `stays live while the database is unreachable`() {
        // The whole point of the split: a database outage must not make the
        // process look dead, because Docker restarts on a failed liveness probe
        // and a restart cannot fix someone else's database.
        val service = HealthService(FakeDatabaseHealth(reachable = false))
        assertTrue(service.isLive())
    }

    @Test
    fun `liveness never touches the database`() {
        val databaseHealth = FakeDatabaseHealth(reachable = true)
        val service = HealthService(databaseHealth)

        service.isLive()

        assertEquals(0, databaseHealth.callCount)
    }

    @Test
    fun `readiness reflects the database recovering`() {
        val databaseHealth = FakeDatabaseHealth(reachable = false)
        val service = HealthService(databaseHealth)
        assertFalse(service.isReady())

        databaseHealth.reachable = true

        assertTrue(service.isReady())
    }
}
