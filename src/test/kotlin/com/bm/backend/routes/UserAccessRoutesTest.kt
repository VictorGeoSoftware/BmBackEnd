package com.bm.backend.routes

import com.bm.backend.models.UserTier
import com.bm.backend.services.AccessControlService
import com.bm.backend.testing.InMemoryGrantedUsersRepository
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UserAccessRoutesTest {

    @Test
    fun `basic access has no premium capabilities`() = verifyAccess(
        tier = UserTier.BASIC,
        expectedCapabilities = emptyList()
    )

    @Test
    fun `premium access exposes comprehensive PDF capability`() = verifyAccess(
        tier = UserTier.PREMIUM,
        expectedCapabilities = listOf("COMPREHENSIVE_COMPARATOR_PDF")
    )

    private fun verifyAccess(tier: UserTier, expectedCapabilities: List<String>) = testApplication {
        environment { config = MapApplicationConfig() }
        val grants = InMemoryGrantedUsersRepository().apply {
            insert("user@example.com", tier)
        }
        val authenticatedUser = AuthenticatedFirebaseUser(
            uid = "uid-1",
            email = "user@example.com",
            name = "User",
            tokenIssuedAt = Instant.ofEpochSecond(1),
            tokenExpiresAt = Instant.ofEpochSecond(2)
        )
        application {
            install(ContentNegotiation) { json() }
            routing {
                userAccessRoutes(AccessControlService(grants)) { authenticatedUser }
            }
        }

        val response = client.get("/me/access") {
            header(HttpHeaders.Authorization, "Bearer valid-token")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val payload = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(tier.name, payload.getValue("tier").jsonPrimitive.content)
        assertEquals(
            expectedCapabilities,
            payload.getValue("capabilities").jsonArray.map { it.jsonPrimitive.content }
        )
        assertTrue(payload.keys == setOf("tier", "capabilities"))
    }
}
