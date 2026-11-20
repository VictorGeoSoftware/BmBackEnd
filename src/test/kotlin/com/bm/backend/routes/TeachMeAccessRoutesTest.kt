package com.bm.backend.routes

import com.bm.backend.services.AdminAccessControlService
import com.bm.backend.services.TeachMeAccessPolicy
import com.bm.backend.testing.InMemoryAdminUsersRepository
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.time.Instant
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class TeachMeAccessRoutesTest {
    @Test
    fun `only a named current admin in QA is granted access`() {
        checkAccess("qa", " , VICTOR@example.com ,", HttpStatusCode.NoContent)
        checkAccess("qa", "other@example.com", HttpStatusCode.Forbidden)
        checkAccess("qa", " , ", HttpStatusCode.Forbidden)
        checkAccess("qa", null, HttpStatusCode.Forbidden)
        checkAccess("qa", "victor@example.com", HttpStatusCode.Forbidden, isAdmin = false)
    }

    @Test
    fun `anonymous requests are unauthorized`() {
        checkAccess("qa", "victor@example.com", HttpStatusCode.Unauthorized, signedIn = false)
    }

    @Test
    fun `route is absent outside QA even with an allowlist`() {
        checkAccess("prod", "victor@example.com", HttpStatusCode.NotFound)
        checkAccess("local", "victor@example.com", HttpStatusCode.NotFound)
    }

    private fun checkAccess(
        environmentName: String,
        allowedEmails: String?,
        expected: HttpStatusCode,
        isAdmin: Boolean = true,
        signedIn: Boolean = true
    ) = testApplication {
        environment { config = MapApplicationConfig() }
        val admins = InMemoryAdminUsersRepository().apply { if (isAdmin) add("victor@example.com") }
        application {
            install(ContentNegotiation) { json() }
            routing {
                teachMeAccessRoutes(AdminAccessControlService(admins), TeachMeAccessPolicy(environmentName, allowedEmails)) {
                    assertEquals("verified-token", it)
                    AuthenticatedFirebaseUser("uid", " Victor@Example.com ", null, Instant.EPOCH, Instant.MAX)
                }
            }
        }
        val response = client.get("/teach-me/check-access") {
            if (signedIn) header(HttpHeaders.Authorization, "Bearer verified-token")
        }
        assertEquals(expected, response.status)
        if (expected == HttpStatusCode.NoContent) {
            assertEquals("", response.bodyAsText())
            assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
            admins.remove("victor@example.com")
            val afterRevocation = client.get("/teach-me/check-access") {
                header(HttpHeaders.Authorization, "Bearer verified-token")
            }
            assertEquals(HttpStatusCode.Forbidden, afterRevocation.status)
        }
    }
}
