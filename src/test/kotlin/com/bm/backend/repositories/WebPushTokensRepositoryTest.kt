package com.bm.backend.repositories

import com.bm.backend.database.WebPushTokensDb
import com.bm.backend.testing.DockerAvailable
import com.bm.backend.testing.PostgresTestSetup
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/** Postgres-backed contract tests for WebPushTokensRepository (V14). */
class WebPushTokensRepositoryTest {

    companion object {
        @BeforeAll
        @JvmStatic
        fun startPostgres() {
            Assumptions.assumeTrue(DockerAvailable.check(), "Docker not available")
            PostgresTestSetup.ensureStarted()
        }
    }

    private val repository = WebPushTokensRepository()

    @BeforeEach
    fun resetSchema() {
        PostgresTestSetup.resetSchema()
    }

    private fun rows(): List<Triple<String, String, String>> = transaction {
        WebPushTokensDb.selectAll().map {
            Triple(it[WebPushTokensDb.token], it[WebPushTokensDb.uid], it[WebPushTokensDb.platform])
        }.sortedBy { it.first }
    }

    @Test
    fun `upsert inserts then reassigns the same token`() {
        repository.upsert("tok-1", "uid-a", "a@example.com", "android")
        repository.upsert("tok-1", "uid-b", "b@example.com", "ios")

        assertEquals(listOf(Triple("tok-1", "uid-b", "ios")), rows())
    }

    @Test
    fun `deleteByTokenForUid only deletes the owner's token`() {
        repository.upsert("tok-1", "uid-a", "a@example.com", "android")

        assertEquals(0, repository.deleteByTokenForUid("tok-1", "uid-b"))
        assertEquals(1, repository.deleteByTokenForUid("tok-1", "uid-a"))
        assertEquals(0, repository.deleteByTokenForUid("tok-1", "uid-a"))
        assertEquals(emptyList(), rows())
    }

    @Test
    fun `deleteByEmail is case-insensitive and keeps other accounts`() {
        repository.upsert("tok-1", "uid-a", "a@example.com", "android")
        repository.upsert("tok-2", "uid-a", "a@example.com", "desktop")
        repository.upsert("tok-3", "uid-b", "b@example.com", "ios")

        assertEquals(2, repository.deleteByEmail("A@Example.com"))
        assertEquals(listOf(Triple("tok-3", "uid-b", "ios")), rows())
    }

    @Test
    fun `allTokens lists every token and deleteByTokens removes only the given ones`() {
        repository.upsert("tok-1", "uid-a", "a@example.com", "android")
        repository.upsert("tok-2", "uid-a", "a@example.com", "desktop")
        repository.upsert("tok-3", "uid-b", "b@example.com", "ios")

        assertEquals(listOf("tok-1", "tok-2", "tok-3"), repository.allTokens().sorted())
        assertEquals(0, repository.deleteByTokens(emptyList()))
        assertEquals(2, repository.deleteByTokens(listOf("tok-1", "tok-3", "missing")))
        assertEquals(listOf(Triple("tok-2", "uid-a", "desktop")), rows())
    }
}
