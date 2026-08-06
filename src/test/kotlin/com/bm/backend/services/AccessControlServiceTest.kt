package com.bm.backend.services

import com.bm.backend.testing.InMemoryGrantedUsersRepository
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AccessControlServiceTest {

    private fun serviceWith(vararg emails: String): AccessControlService {
        val repository = InMemoryGrantedUsersRepository()
        emails.forEach { repository.insert(it) }
        return AccessControlService(repository)
    }

    @Test
    fun `permits granted accounts case-insensitively`() {
        val service = serviceWith("tester@example.com")
        assertTrue(service.isEmailAllowed("tester@example.com"))
        assertTrue(service.isEmailAllowed("  TESTER@EXAMPLE.COM "))
    }

    @Test
    fun `denies non-granted and blank accounts`() {
        val service = serviceWith("tester@example.com")
        assertFalse(service.isEmailAllowed("intruder@example.com"))
        assertFalse(service.isEmailAllowed(null))
        assertFalse(service.isEmailAllowed("   "))
    }

    @Test
    fun `fails closed when no grants exist`() {
        val service = serviceWith()
        assertFalse(service.isEmailAllowed("anyone@example.com"))
    }

    @Test
    fun `revoked grant denies access immediately`() {
        val repository = InMemoryGrantedUsersRepository()
        repository.insert("tester@example.com")
        val service = AccessControlService(repository)

        assertTrue(service.isEmailAllowed("tester@example.com"))
        repository.deleteByEmail("tester@example.com")
        assertFalse(service.isEmailAllowed("tester@example.com"))
    }
}
