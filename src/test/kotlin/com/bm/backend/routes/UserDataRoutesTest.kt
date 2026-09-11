package com.bm.backend.routes

import com.bm.backend.services.AccessControlService
import com.bm.backend.services.UserDataService
import com.bm.backend.testing.InMemoryGrantedUsersRepository
import com.bm.backend.testing.InMemoryUserDataRepository
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals

class UserDataRoutesTest {

    @Test
    fun `user sync stores verified token email instead of request email`() = testApplication {
        environment { config = MapApplicationConfig() }
        val userDataRepository = InMemoryUserDataRepository()
        val grants = InMemoryGrantedUsersRepository().apply {
            insert("verified@example.com")
        }
        val authenticatedUser = AuthenticatedFirebaseUser(
            uid = "uid-1",
            email = "VERIFIED@example.com",
            name = "Verified User",
            tokenIssuedAt = Instant.ofEpochSecond(1),
            tokenExpiresAt = Instant.ofEpochSecond(2)
        )

        application {
            install(ContentNegotiation) { json() }
            routing {
                userDataRoutes(
                    userDataService = UserDataService(userDataRepository),
                    accessControlService = AccessControlService(grants),
                    verifyToken = { authenticatedUser }
                )
            }
        }

        val response = client.post("/user-data") {
            header(HttpHeaders.Authorization, "Bearer valid-token")
            contentType(ContentType.Application.Json)
            setBody(
                """{
                    "uid":"uid-1",
                    "email":"attacker@example.com",
                    "providerIds":["google.com"],
                    "phoneUuid":"device-a"
                }""".trimIndent()
            )
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("verified@example.com", userDataRepository.findEmail("uid-1"))
    }
}
