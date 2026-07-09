package com.bm.backend.repositories

import com.bm.backend.database.UserDataDb
import com.bm.backend.security.EncryptionUtils
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Abstract contract tests for [UserDataRepository].
 * Subclasses provide DB setup; tests run identically on SQLite and Postgres.
 */
abstract class AbstractUserDataRepositoryTest {

    protected lateinit var repository: UserDataRepository

    abstract fun initDatabase()

    @BeforeEach
    fun setUp() {
        initDatabase()
        EncryptionUtils.init(EncryptionUtils.generateKey())
        transaction { UserDataDb.deleteAll() }
        repository = UserDataRepository()
    }

    @Test
    fun `upsertUserData inserts a new row with encrypted PII at rest`() {
        repository.upsertUserData(
            uid = "uid-1", email = "alice@example.com", displayName = "Alice",
            photoURL = "https://example.com/a.png",
            providerIds = listOf("google.com", "password"),
            tokenIssuedAt = Instant.ofEpochSecond(1_000L),
            tokenExpiresAt = Instant.ofEpochSecond(2_000L),
            phoneUuid = null
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
        assertEquals("alice@example.com", EncryptionUtils.decrypt(raw.first!!))
        assertEquals("Alice", EncryptionUtils.decrypt(raw.second!!))
        assertEquals("https://example.com/a.png", EncryptionUtils.decrypt(raw.third!!))
    }

    @Test
    fun `upsertUserData updates existing row when uid matches`() {
        repository.upsertUserData(
            uid = "uid-1", email = "alice@example.com", displayName = "Alice",
            photoURL = null, providerIds = listOf("password"),
            tokenIssuedAt = Instant.ofEpochSecond(1_000L),
            tokenExpiresAt = Instant.ofEpochSecond(2_000L),
            phoneUuid = null
        )
        Thread.sleep(2)
        repository.upsertUserData(
            uid = "uid-1", email = "alice+new@example.com", displayName = "Alice 2",
            photoURL = null, providerIds = listOf("password", "google.com"),
            tokenIssuedAt = Instant.ofEpochSecond(3_000L),
            tokenExpiresAt = Instant.ofEpochSecond(4_000L),
            phoneUuid = null
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
        val rowCount = transaction { UserDataDb.selectAll().count() }
        assertEquals(1L, rowCount)
        assertEquals("alice+new@example.com", decryptedEmail)
        assertEquals("password,google.com", providerIds)
        assertTrue((updatedAt as Instant) >= (createdAt as Instant))
    }

    @Test
    fun `upsertUserData stores nullable PII as null (not encrypted empty string)`() {
        repository.upsertUserData(
            uid = "uid-2", email = null, displayName = null, photoURL = null,
            providerIds = emptyList(),
            tokenIssuedAt = Instant.ofEpochSecond(1L),
            tokenExpiresAt = Instant.ofEpochSecond(2L),
            phoneUuid = null
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

    @Test
    fun `upsertUserData keeps the first registered phone_uuid (first-device-wins)`() {
        repository.upsertUserData(
            uid = "uid-3", email = "bob@example.com", displayName = "Bob", photoURL = null,
            providerIds = listOf("google.com"),
            tokenIssuedAt = Instant.ofEpochSecond(1L),
            tokenExpiresAt = Instant.ofEpochSecond(2L),
            phoneUuid = "device-a"
        )
        repository.upsertUserData(
            uid = "uid-3", email = "bob@example.com", displayName = "Bob", photoURL = null,
            providerIds = listOf("google.com"),
            tokenIssuedAt = Instant.ofEpochSecond(3L),
            tokenExpiresAt = Instant.ofEpochSecond(4L),
            phoneUuid = "device-b"
        )
        val storedPhoneUuid = transaction {
            UserDataDb.selectAll().single()[UserDataDb.phoneUuid]
        }
        assertEquals("device-a", storedPhoneUuid, "the first registered device must be retained")
    }

    @Test
    fun `findPhoneUuid returns null when unbound and the stored value once bound`() {
        kotlin.test.assertNull(repository.findPhoneUuid("missing"), "unknown uid must return null")

        repository.upsertUserData(
            uid = "uid-4", email = "carol@example.com", displayName = "Carol", photoURL = null,
            providerIds = listOf("google.com"),
            tokenIssuedAt = Instant.ofEpochSecond(1L),
            tokenExpiresAt = Instant.ofEpochSecond(2L),
            phoneUuid = null
        )
        kotlin.test.assertNull(repository.findPhoneUuid("uid-4"), "row without a device must return null")

        repository.upsertUserData(
            uid = "uid-4", email = "carol@example.com", displayName = "Carol", photoURL = null,
            providerIds = listOf("google.com"),
            tokenIssuedAt = Instant.ofEpochSecond(3L),
            tokenExpiresAt = Instant.ofEpochSecond(4L),
            phoneUuid = "device-c"
        )
        assertEquals("device-c", repository.findPhoneUuid("uid-4"))
    }

    @Test
    fun `clearPhoneUuidByEmail unbinds the matching account and reports the count`() {
        repository.upsertUserData(
            uid = "uid-5", email = "Dave@Example.com", displayName = "Dave", photoURL = null,
            providerIds = listOf("google.com"),
            tokenIssuedAt = Instant.ofEpochSecond(1L),
            tokenExpiresAt = Instant.ofEpochSecond(2L),
            phoneUuid = "device-d"
        )

        assertEquals(0, repository.clearPhoneUuidByEmail("someone-else@example.com"))
        assertEquals("device-d", repository.findPhoneUuid("uid-5"))

        // Email match is case-insensitive against the decrypted value.
        assertEquals(1, repository.clearPhoneUuidByEmail("dave@example.com"))
        kotlin.test.assertNull(repository.findPhoneUuid("uid-5"), "binding must be cleared after reset")
    }
}
