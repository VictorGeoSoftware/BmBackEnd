package com.bm.backend.services

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MetricsAuthServiceTest {

    private val service = MetricsAuthService(token = "s3cret-token")

    @Test
    fun `accepts the configured bearer token`() {
        assertTrue(service.isAuthorized("Bearer s3cret-token"))
    }

    @Test
    fun `accepts a case-insensitive bearer scheme`() {
        // Nothing in the RFC requires the scheme to be capitalised, and some
        // scrapers send "bearer".
        assertTrue(service.isAuthorized("bearer s3cret-token"))
        assertTrue(service.isAuthorized("BEARER s3cret-token"))
    }

    @Test
    fun `rejects a wrong token`() {
        assertFalse(service.isAuthorized("Bearer wrong-token"))
    }

    @Test
    fun `rejects a missing or malformed header`() {
        assertFalse(service.isAuthorized(null))
        assertFalse(service.isAuthorized(""))
        assertFalse(service.isAuthorized("   "))
        assertFalse(service.isAuthorized("Bearer"))
        assertFalse(service.isAuthorized("Bearer "))
        // Right secret, wrong scheme.
        assertFalse(service.isAuthorized("s3cret-token"))
        assertFalse(service.isAuthorized("Basic s3cret-token"))
    }

    @Test
    fun `rejects a token that is merely a prefix of the secret`() {
        assertFalse(service.isAuthorized("Bearer s3cret"))
        assertFalse(service.isAuthorized("Bearer s3cret-token-extra"))
    }

    @Test
    fun `fails closed when no token is configured`() {
        // An unset METRICS_TOKEN must disable the endpoint, never open it.
        listOf(null, "", "   ").forEach { raw ->
            val disabled = MetricsAuthService(token = raw)
            assertFalse(disabled.enabled)
            assertFalse(disabled.isAuthorized("Bearer anything"))
            assertFalse(disabled.isAuthorized(null))
            assertFalse(disabled.isAuthorized("Bearer "))
        }
    }

    @Test
    fun `fromEnv trims and treats blank as unset`() {
        assertTrue(MetricsAuthService.fromEnv("  s3cret-token  ").isAuthorized("Bearer s3cret-token"))
        assertFalse(MetricsAuthService.fromEnv("   ").enabled)
        assertFalse(MetricsAuthService.fromEnv(null).enabled)
    }
}
