package com.bm.backend.services

import com.bm.backend.testing.InMemoryAdminUsersRepository
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AdminAccessControlServiceTest {

    private fun serviceWith(vararg emails: String): AdminAccessControlService {
        val repository = InMemoryAdminUsersRepository()
        emails.forEach { repository.add(it) }
        return AdminAccessControlService(repository)
    }

    @Test
    fun `permits admin accounts case-insensitively`() {
        val service = serviceWith("admin@example.com")
        assertTrue(service.isAdmin("admin@example.com"))
        assertTrue(service.isAdmin("  ADMIN@EXAMPLE.COM "))
    }

    @Test
    fun `denies non-admin and blank accounts`() {
        val service = serviceWith("admin@example.com")
        assertFalse(service.isAdmin("intruder@example.com"))
        assertFalse(service.isAdmin(null))
        assertFalse(service.isAdmin("   "))
    }

    @Test
    fun `fails closed when no admins exist`() {
        val service = serviceWith()
        assertFalse(service.isAdmin("anyone@example.com"))
    }

    @Test
    fun `revoked admin loses access immediately`() {
        val repository = InMemoryAdminUsersRepository()
        repository.add("admin@example.com")
        val service = AdminAccessControlService(repository)

        assertTrue(service.isAdmin("admin@example.com"))
        repository.remove("admin@example.com")
        assertFalse(service.isAdmin("admin@example.com"))
    }
}
