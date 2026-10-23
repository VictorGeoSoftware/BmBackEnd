package com.bm.backend.repositories

import com.bm.backend.models.UserTier
import com.bm.backend.testing.DockerAvailable
import com.bm.backend.testing.PostgresTestSetup
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Postgres-backed contract tests for GrantedUsersRepository. */
class GrantedUsersRepositoryTest {

    companion object {
        @BeforeAll
        @JvmStatic
        fun startPostgres() {
            Assumptions.assumeTrue(DockerAvailable.check(), "Docker not available")
            PostgresTestSetup.ensureStarted()
        }
    }

    private val repository = GrantedUsersRepository()

    @BeforeEach
    fun resetSchema() {
        PostgresTestSetup.resetSchema()
        // V8 seeds the production allowlist; clear it so each test starts empty.
        GrantedUsersRepository().findAll().forEach { grant ->
            GrantedUsersRepository().deleteByEmail(grant.email)
        }
    }

    @Test
    fun `insert then existsByEmail round-trips`() {
        assertTrue(repository.insert("tester@example.com"))
        assertTrue(repository.existsByEmail("tester@example.com"))
        assertFalse(repository.existsByEmail("other@example.com"))
        assertEquals(UserTier.BASIC, repository.findByEmail("tester@example.com")?.tier)
    }

    @Test
    fun `insert is idempotent-safe via duplicate rejection`() {
        assertTrue(repository.insert("tester@example.com"))
        assertFalse(repository.insert("tester@example.com"))
        assertEquals(1, repository.findAll().size)
    }

    @Test
    fun `deleteByEmail removes only the matching grant`() {
        repository.insert("a@example.com")
        repository.insert("b@example.com")

        assertEquals(1, repository.deleteByEmail("a@example.com"))
        assertEquals(0, repository.deleteByEmail("a@example.com"))

        assertFalse(repository.existsByEmail("a@example.com"))
        assertTrue(repository.existsByEmail("b@example.com"))
    }

    @Test
    fun `findAll returns grants newest first`() {
        repository.insert("first@example.com")
        Thread.sleep(5)
        repository.insert("second@example.com")

        val emails = repository.findAll().map { it.email }
        assertEquals(listOf("second@example.com", "first@example.com"), emails)
    }

    @Test
    fun `updateTier changes only an existing grant`() {
        repository.insert("tester@example.com")

        assertEquals(1, repository.updateTier("tester@example.com", UserTier.PREMIUM))
        assertEquals(UserTier.PREMIUM, repository.findByEmail("tester@example.com")?.tier)
        assertEquals(0, repository.updateTier("missing@example.com", UserTier.PREMIUM))
    }
}
