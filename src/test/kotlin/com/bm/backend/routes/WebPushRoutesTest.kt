package com.bm.backend.routes

import com.bm.backend.services.AccessControlService
import com.bm.backend.services.WebPushTokensService
import com.bm.backend.testing.InMemoryGrantedUsersRepository
import com.bm.backend.testing.InMemoryWebPushTokensRepository
import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WebPushRoutesTest {

    private val tokens = InMemoryWebPushTokensRepository()
    private val grants = InMemoryGrantedUsersRepository().apply {
        insert("alice@example.com")
        insert("bob@example.com")
    }

    private fun user(uid: String, email: String) = AuthenticatedFirebaseUser(
        uid = uid,
        email = email,
        name = null,
        tokenIssuedAt = Instant.ofEpochSecond(1),
        tokenExpiresAt = Instant.ofEpochSecond(2)
    )

    private val usersByBearer = mapOf(
        "alice" to user("uid-alice", "Alice@example.com"),
        "bob" to user("uid-bob", "bob@example.com"),
        "mallory" to user("uid-mallory", "mallory@example.com")
    )

    private fun ApplicationTestBuilder.setUp() {
        environment { config = MapApplicationConfig() }
        application {
            install(ContentNegotiation) { json() }
            routing {
                webPushRoutes(
                    webPushTokensService = WebPushTokensService(tokens),
                    accessControlService = AccessControlService(grants),
                    verifyToken = { bearer -> usersByBearer.getValue(bearer) }
                )
            }
        }
    }

    private suspend fun HttpClient.register(as_: String, body: String): HttpResponse =
        post("/push/web-tokens") {
            header(HttpHeaders.Authorization, "Bearer $as_")
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private suspend fun HttpClient.unregister(as_: String, body: String): HttpResponse =
        delete("/push/web-tokens") {
            header(HttpHeaders.Authorization, "Bearer $as_")
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    @Test
    fun `register stores the token with the verified uid and email`() = testApplication {
        setUp()
        val response = client.register("alice", """{"token":"tok-1","platform":"android"}""")

        assertEquals(HttpStatusCode.OK, response.status)
        val row = tokens.find("tok-1")!!
        assertEquals("uid-alice", row.uid)
        assertEquals("alice@example.com", row.email)
        assertEquals("android", row.platform)
    }

    @Test
    fun `register reassigns a token that moves to another account`() = testApplication {
        setUp()
        client.register("alice", """{"token":"tok-1","platform":"android"}""")
        val response = client.register("bob", """{"token":"tok-1","platform":"ios"}""")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(1, tokens.all().size)
        assertEquals("uid-bob", tokens.find("tok-1")!!.uid)
        assertEquals("ios", tokens.find("tok-1")!!.platform)
    }

    @Test
    fun `register rejects blank, too long tokens and unknown platforms`() = testApplication {
        setUp()
        val longToken = "x".repeat(WebPushTokensService.MAX_TOKEN_LENGTH + 1)
        listOf(
            """{"token":"  ","platform":"android"}""",
            """{"platform":"android"}""",
            """{"token":"$longToken","platform":"android"}""",
            """{"token":"tok-1","platform":"windows"}""",
            """{"token":"tok-1"}"""
        ).forEach { body ->
            assertEquals(HttpStatusCode.BadRequest, client.register("alice", body).status, body.take(40))
        }
        assertTrue(tokens.all().isEmpty())
    }

    @Test
    fun `non-granted users are rejected`() = testApplication {
        setUp()
        val response = client.register("mallory", """{"token":"tok-1","platform":"android"}""")

        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertTrue(tokens.all().isEmpty())
    }

    @Test
    fun `delete removes only the caller's own token and is idempotent`() = testApplication {
        setUp()
        client.register("alice", """{"token":"tok-a","platform":"android"}""")
        client.register("bob", """{"token":"tok-b","platform":"desktop"}""")

        assertEquals(HttpStatusCode.OK, client.unregister("alice", """{"token":"tok-b"}""").status)
        assertEquals("uid-bob", tokens.find("tok-b")?.uid)

        assertEquals(HttpStatusCode.OK, client.unregister("alice", """{"token":"tok-a"}""").status)
        assertNull(tokens.find("tok-a"))

        assertEquals(HttpStatusCode.OK, client.unregister("alice", """{"token":"tok-a"}""").status)
        assertEquals(HttpStatusCode.BadRequest, client.unregister("alice", """{"token":""}""").status)
    }
}
