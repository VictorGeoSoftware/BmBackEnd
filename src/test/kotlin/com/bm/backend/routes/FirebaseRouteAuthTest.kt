package com.bm.backend.routes

import com.bm.backend.services.AccessControlService
import com.bm.backend.models.UserTier
import com.bm.backend.testing.InMemoryGrantedUsersRepository
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.call
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals

class FirebaseRouteAuthTest {

    private val authenticatedUser = AuthenticatedFirebaseUser(
        uid = "uid-1",
        email = " Granted@Example.com ",
        name = "Granted User",
        tokenIssuedAt = Instant.ofEpochSecond(1),
        tokenExpiresAt = Instant.ofEpochSecond(2)
    )

    @Test
    fun `granted guard rejects requests without a Firebase token`() = testApplication {
        environment { config = MapApplicationConfig() }
        val accessControlService = AccessControlService(InMemoryGrantedUsersRepository())
        application {
            install(ContentNegotiation) { json() }
            routing {
                get("/protected") {
                    call.requireGrantedFirebaseUser(accessControlService) { authenticatedUser }
                        ?: return@get
                    call.respond(HttpStatusCode.OK)
                }
            }
        }

        assertEquals(HttpStatusCode.Unauthorized, client.get("/protected").status)
    }

    @Test
    fun `granted guard rejects authenticated accounts without an app grant`() = testApplication {
        environment { config = MapApplicationConfig() }
        val accessControlService = AccessControlService(InMemoryGrantedUsersRepository())
        application {
            install(ContentNegotiation) { json() }
            routing {
                get("/protected") {
                    call.requireGrantedFirebaseUser(accessControlService) { authenticatedUser }
                        ?: return@get
                    call.respond(HttpStatusCode.OK)
                }
            }
        }

        val response = client.get("/protected") {
            header(HttpHeaders.Authorization, "Bearer valid-token")
        }

        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `granted guard returns normalized verified identity for granted accounts`() = testApplication {
        environment { config = MapApplicationConfig() }
        val grants = InMemoryGrantedUsersRepository().apply {
            insert("granted@example.com")
        }
        val accessControlService = AccessControlService(grants)
        application {
            install(ContentNegotiation) { json() }
            routing {
                get("/protected") {
                    val user = call.requireGrantedFirebaseUser(accessControlService) { authenticatedUser }
                        ?: return@get
                    call.respond(HttpStatusCode.OK, "${user.email}:${user.tier}")
                }
            }
        }

        val response = client.get("/protected") {
            header(HttpHeaders.Authorization, "Bearer valid-token")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("granted@example.com:BASIC", response.bodyAsText())
    }

    @Test
    fun `granted guard returns premium tier from the database`() = testApplication {
        environment { config = MapApplicationConfig() }
        val grants = InMemoryGrantedUsersRepository().apply {
            insert("granted@example.com", UserTier.PREMIUM)
        }
        val accessControlService = AccessControlService(grants)
        application {
            install(ContentNegotiation) { json() }
            routing {
                get("/protected") {
                    val user = call.requireGrantedFirebaseUser(accessControlService) { authenticatedUser }
                        ?: return@get
                    call.respond(HttpStatusCode.OK, user.tier.name)
                }
            }
        }

        val response = client.get("/protected") {
            header(HttpHeaders.Authorization, "Bearer valid-token")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("PREMIUM", response.bodyAsText())
    }
}
