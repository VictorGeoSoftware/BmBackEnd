package com.bm.backend.services

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InternalApiAuthServiceTest {

    private val service = InternalApiAuthService(token = "s3cret-token")

    @Test
    fun `accepts the configured token`() {
        assertTrue(service.isAuthorized("s3cret-token"))
        assertTrue(service.isAuthorized("  s3cret-token  "))
    }

    @Test
    fun `rejects a wrong, partial or missing token`() {
        assertFalse(service.isAuthorized("wrong-token"))
        assertFalse(service.isAuthorized("s3cret"))
        assertFalse(service.isAuthorized("s3cret-token-extra"))
        assertFalse(service.isAuthorized("Bearer s3cret-token"))
        assertFalse(service.isAuthorized(null))
        assertFalse(service.isAuthorized(""))
        assertFalse(service.isAuthorized("   "))
    }

    @Test
    fun `fails closed when no token is configured`() {
        listOf(null, "", "   ").forEach { raw ->
            val disabled = InternalApiAuthService(token = raw)
            assertFalse(disabled.enabled)
            assertFalse(disabled.isAuthorized("anything"))
            assertFalse(disabled.isAuthorized(null))
            assertFalse(disabled.isAuthorized(""))
        }
    }

    @Test
    fun `fromEnv trims and treats blank as unset`() {
        assertTrue(InternalApiAuthService.fromEnv("  s3cret-token  ").isAuthorized("s3cret-token"))
        assertFalse(InternalApiAuthService.fromEnv("   ").enabled)
        assertFalse(InternalApiAuthService.fromEnv(null).enabled)
    }
}
