package com.bm.backend.services

import com.bm.backend.testing.InMemoryUserDataRepository
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals

/**
 * Service-level unit tests for [UserDataService], exercised through an
 * in-memory fake of [com.bm.backend.repositories.ports.UserDataRepositoryPort].
 */
class UserDataServiceTest {

    private fun sync(service: UserDataService, uid: String, email: String? = "user@example.com") {
        service.upsertUserData(
            uid = uid,
            email = email,
            displayName = null,
            photoURL = null,
            providerIds = listOf("google.com"),
            tokenIssuedAt = Instant.ofEpochSecond(1L),
            tokenExpiresAt = Instant.ofEpochSecond(2L)
        )
    }

    @Test
    fun `repeated logins for the same account are accepted and update the stored data`() {
        val fake = InMemoryUserDataRepository()
        val service = UserDataService(fake)

        sync(service, uid = "uid-1", email = "first@example.com")
        sync(service, uid = "uid-1", email = "second@example.com")

        assertEquals("second@example.com", fake.findEmail("uid-1"))
    }
}
