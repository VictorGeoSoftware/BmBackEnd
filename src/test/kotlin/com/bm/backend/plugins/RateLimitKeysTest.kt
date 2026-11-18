package com.bm.backend.plugins

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class RateLimitKeysTest {

    private fun key(auth: String?, realIp: String? = null, peer: String = "10.0.0.9") =
        RateLimitKeys.callerKey(auth, realIp, peer)

    @Test
    fun `bearer callers are keyed by a hash of their token`() {
        val a = key("Bearer token-a", realIp = "1.1.1.1")
        assertTrue(a.startsWith("user:"))
        assertEquals(a, key("Bearer token-a", realIp = "2.2.2.2"), "same session, different IP = same bucket")
        assertEquals(a, key("bearer token-a"), "scheme is case-insensitive")
        assertNotEquals(a, key("Bearer token-b"))
    }

    @Test
    fun `the raw token never appears in the key`() {
        val token = "eyJhbGciOiJSUzI1NiJ9.secret-payload.sig"
        assertFalse(key("Bearer $token").contains(token))
        assertFalse(key("Bearer $token").contains("secret-payload"))
    }

    @Test
    fun `callers without a usable bearer token are keyed by client IP`() {
        listOf(null, "", "Bearer", "Bearer   ", "Basic abc", "token-without-scheme").forEach { auth ->
            assertEquals("ip:1.1.1.1", key(auth, realIp = "1.1.1.1"), "auth=$auth")
        }
    }

    @Test
    fun `X-Real-IP from nginx wins, socket peer is the fallback for internal callers`() {
        assertEquals("ip:203.0.113.7", key(null, realIp = " 203.0.113.7 "))
        assertEquals("ip:172.18.0.6", key(null, realIp = null, peer = "172.18.0.6"))
        assertEquals("ip:172.18.0.6", key(null, realIp = "  ", peer = "172.18.0.6"))
    }
}
