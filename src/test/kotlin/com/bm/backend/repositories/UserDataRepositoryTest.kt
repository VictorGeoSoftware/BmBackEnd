package com.bm.backend.repositories

import com.bm.backend.database.DatabaseFactory
import com.bm.backend.database.UserDataDb
import com.bm.backend.security.EncryptionUtils
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Characterization tests for [UserDataRepository] (Phase A1).
 *
 * Pins:
 * - PII fields (email, displayName, photoURL) are AES-GCM encrypted at rest.
 * - Upsert is keyed by uid; createdAt is set once, updatedAt on every write.
 */
class UserDataRepositoryTest {

    private lateinit var repository: UserDataRepository

    @BeforeEach
    fun setUp() {
        DatabaseFactory.initTestDatabase()
        EncryptionUtils.init(EncryptionUtils.generateKey())
        repository = UserDataRepository()
    }

    @AfterEach
    fun tearDown() {
        java.io.File("test_price_tables.db").delete()
    }

    @Test
    fun `upsertUserData inserts a new row with encrypted PII at rest`() {
        repository.upsertUserData(
            uid = "uid-1",
            email = "alice@example.com",
            displayName = "Alice",
            photoURL = "https://example.com/a.png",
            providerIds = listOf("google.com", "password"),
            tokenIssuedAt = 1_000L,
            tokenExpiresAt = 2_000L
        )

        val raw = transaction {
            UserDataDb.selectAll().single().let {
                Triple(it[UserDataDb.email], it[UserDataDb.displayName], it[UserDataDb.photoURL])
            }
        }

        assertNotNull(raw.first)
        assertNotEquals("alice@example.com", raw.first, "email must not be stored in plaintext")
        assertNotEquals("Alice", raw.second, "displayName must not be stored in plaintext")
        assertNotEquals("https://example.com/a.png", raw.third, "photoURL must not be stored in plaintext")

        // And must be decryptable back to the originals
        assertEquals("alice@example.com", EncryptionUtils.decrypt(raw.first!!))
        assertEquals("Alice", EncryptionUtils.decrypt(raw.second!!))
        assertEquals("https://example.com/a.png", EncryptionUtils.decrypt(raw.third!!))
    }

    @Test
    fun `upsertUserData updates existing row when uid matches`() {
        repository.upsertUserData(
            uid = "uid-1",
            email = "alice@example.com",
            displayName = "Alice",
            photoURL = null,
            providerIds = listOf("password"),
            tokenIssuedAt = 1_000L,
            tokenExpiresAt = 2_000L
        )
        Thread.sleep(2) // ensure updatedAt advances on systems with low clock resolution
        repository.upsertUserData(
            uid = "uid-1",
            email = "alice+new@example.com",
            displayName = "Alice 2",
            photoURL = null,
            providerIds = listOf("password", "google.com"),
            tokenIssuedAt = 3_000L,
            tokenExpiresAt = 4_000L
        )

        val (createdAt, updatedAt, providerIds, decryptedEmail) = transaction {
            UserDataDb.selectAll().single().let { row ->
                listOf(
                    row[UserDataDb.createdAt],
                    row[UserDataDb.updatedAt],
                    row[UserDataDb.providerIds],
                    EncryptionUtils.decrypt(row[UserDataDb.email]!!)
                )
            }
        }

        // Single row -> only one user_data record per uid.
        val rowCount = transaction { UserDataDb.selectAll().count() }
        assertEquals(1L, rowCount)
        assertEquals("alice+new@example.com", decryptedEmail)
        assertEquals("password,google.com", providerIds)
        assertTrue((updatedAt as Long) >= (createdAt as Long))
    }

    @Test
    fun `upsertUserData stores nullable PII as null (not encrypted empty string)`() {
        repository.upsertUserData(
            uid = "uid-2",
            email = null,
            displayName = null,
            photoURL = null,
            providerIds = emptyList(),
            tokenIssuedAt = 1L,
            tokenExpiresAt = 2L
        )

        val (email, displayName, photoURL) = transaction {
            UserDataDb.selectAll().single().let {
                Triple(it[UserDataDb.email], it[UserDataDb.displayName], it[UserDataDb.photoURL])
            }
        }
        kotlin.test.assertNull(email)
        kotlin.test.assertNull(displayName)
        kotlin.test.assertNull(photoURL)
    }
}
