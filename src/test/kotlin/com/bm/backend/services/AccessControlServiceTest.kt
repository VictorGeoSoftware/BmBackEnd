package com.bm.backend.services

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AccessControlServiceTest {

    @Test
    fun `disabled when no emails configured allows everyone`() {
        val service = AccessControlService(allowedEmails = emptySet())
        assertFalse(service.enabled)
        assertTrue(service.isEmailAllowed("anyone@example.com"))
    }

    @Test
    fun `permits listed accounts case-insensitively and denies others`() {
        val service = AccessControlService(allowedEmails = setOf("Tester@Example.com"))
        assertTrue(service.enabled)
        assertTrue(service.isEmailAllowed("tester@example.com"))
        assertTrue(service.isEmailAllowed("  TESTER@EXAMPLE.COM "))
        assertFalse(service.isEmailAllowed("intruder@example.com"))
        assertFalse(service.isEmailAllowed(null))
    }

    @Test
    fun `fromEnv parses comma separated list and ignores blanks`() {
        val service = AccessControlService.fromEnv("alice@example.com, bob@example.com , ")
        assertTrue(service.enabled)
        assertTrue(service.isEmailAllowed("alice@example.com"))
        assertTrue(service.isEmailAllowed("bob@example.com"))
        assertFalse(service.isEmailAllowed("carol@example.com"))
    }

    @Test
    fun `fromEnv with null or blank disables the policy`() {
        assertFalse(AccessControlService.fromEnv(null).enabled)
        assertFalse(AccessControlService.fromEnv("   ").enabled)
        assertTrue(AccessControlService.fromEnv(null).isEmailAllowed("anyone@example.com"))
    }
}
