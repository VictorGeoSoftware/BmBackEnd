package com.bm.backend.services

import com.bm.backend.testing.InMemoryUserActivityRepository
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Service-level unit tests that exercise the [UserActivityRepositoryPort]
 * abstraction via an in-memory fake (no database). Proves Phase A2
 * (ports + DIP) actually decouples services from infrastructure.
 */
class UserActivityServiceTest {

    @Test
    fun `setUserOnline normalizes email and routes through the port`() {
        val fake = InMemoryUserActivityRepository()
        val service = UserActivityService(fake)

        service.setUserOnline(name = "  Alice  ", email = "  Alice@Example.COM ")

        val users = service.getUsersActivity()
        assertEquals(1, users.size)
        val u = users.single()
        assertEquals("alice@example.com", u.email)
        assertEquals("Alice", u.name)
        assertTrue(u.isOnline)
    }

    @Test
    fun `setUserOffline after setUserOnline keeps a single record per email`() {
        val service = UserActivityService(InMemoryUserActivityRepository())

        service.setUserOnline(name = "Bob", email = "bob@example.com")
        service.setUserOffline(name = "Bob", email = "bob@example.com")

        val users = service.getUsersActivity()
        assertEquals(1, users.size)
        assertFalse(users.single().isOnline)
    }

    @Test
    fun `setUserOnline rejects emails without @`() {
        val service = UserActivityService(InMemoryUserActivityRepository())
        assertThrows<IllegalArgumentException> {
            service.setUserOnline(name = "X", email = "not-an-email")
        }
    }

    @Test
    fun `setUserOnline derives name from email when name is blank`() {
        val service = UserActivityService(InMemoryUserActivityRepository())
        service.setUserOnline(name = "   ", email = "carol@example.com")

        assertEquals("carol", service.getUsersActivity().single().name)
    }
}
