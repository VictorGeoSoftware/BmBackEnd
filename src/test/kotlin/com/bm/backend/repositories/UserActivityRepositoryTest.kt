package com.bm.backend.repositories

import com.bm.backend.database.DatabaseFactory
import com.bm.backend.database.UserActivityDb
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.YearMonth
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Characterization tests for [UserActivityRepository] (Phase A1).
 *
 * Pins:
 * - Online/offline transitions are idempotent per email.
 * - monthlyUsageCount resets when monthKey changes.
 * - usageStartedAt is set on first insert and never overwritten afterwards.
 * - getUsersFirstConnection exposes usageStartedAt as `firstConnectedAt`.
 */
class UserActivityRepositoryTest {

    private lateinit var repository: UserActivityRepository

    @BeforeEach
    fun setUp() {
        DatabaseFactory.initTestDatabase()
        repository = UserActivityRepository()
    }

    @AfterEach
    fun tearDown() {
        java.io.File("test_price_tables.db").delete()
    }

    @Test
    fun `setOnline inserts a new active row when email is unknown`() {
        repository.setOnline(name = "Alice", email = "alice@example.com")

        val users = repository.getUsersActivity()
        assertEquals(1, users.size)
        val u = users.single()
        assertEquals("Alice", u.name)
        assertEquals("alice@example.com", u.email)
        assertTrue(u.isOnline)
        assertEquals(0, u.monthlyUsageCount)
        assertNotNull(u.lastConnectedAt)
        assertNull(u.lastDisconnectedAt)
    }

    @Test
    fun `setOnline followed by setOffline updates the same row`() {
        repository.setOnline(name = "Alice", email = "alice@example.com")
        repository.setOffline(name = "Alice", email = "alice@example.com")

        val users = repository.getUsersActivity()
        assertEquals(1, users.size, "Online/offline must not duplicate rows for the same email")
        val u = users.single()
        assertFalse(u.isOnline)
        assertNotNull(u.lastConnectedAt)
        assertNotNull(u.lastDisconnectedAt)
    }

    @Test
    fun `incrementMonthlyUsageCounter increments for current month and creates row if missing`() {
        repository.incrementMonthlyUsageCounter(name = "Bob", email = "bob@example.com")
        repository.incrementMonthlyUsageCounter(name = "Bob", email = "bob@example.com")
        repository.incrementMonthlyUsageCounter(name = "Bob", email = "bob@example.com")

        val users = repository.getUsersActivity()
        val bob = users.single { it.email == "bob@example.com" }
        assertEquals(3, bob.monthlyUsageCount)
    }

    @Test
    fun `monthlyUsageCount resets when month changes`() {
        // Seed a row in a previous month directly via DB to bypass the
        // YearMonth.now() captured by repository.
        val previousMonth = YearMonth.now().minusMonths(1).toString()
        val pastEpoch = System.currentTimeMillis() - 35L * 24 * 3_600_000
        transaction {
            UserActivityDb.insert {
                it[UserActivityDb.name] = "Carol"
                it[UserActivityDb.email] = "carol@example.com"
                it[UserActivityDb.isOnline] = false
                it[UserActivityDb.monthlyUsageCount] = 7
                it[UserActivityDb.monthKey] = previousMonth
                it[UserActivityDb.usageStartedAt] = pastEpoch
                it[UserActivityDb.lastConnectedAt] = pastEpoch
                it[UserActivityDb.lastDisconnectedAt] = pastEpoch
                it[UserActivityDb.updatedAt] = pastEpoch
            }
        }

        // Read should report 0 because the stored monthKey != current monthKey
        val carol = repository.getUsersActivity().single { it.email == "carol@example.com" }
        assertEquals(0, carol.monthlyUsageCount)

        // First increment in the new month must start at 1, not 8
        repository.incrementMonthlyUsageCounter(name = "Carol", email = "carol@example.com")
        val carolAfter = repository.getUsersActivity().single { it.email == "carol@example.com" }
        assertEquals(1, carolAfter.monthlyUsageCount)
    }

    @Test
    fun `usageStartedAt is set on first insert and preserved across transitions`() {
        repository.setOnline(name = "Dan", email = "dan@example.com")
        val firstConnectedAt = repository
            .getUsersFirstConnection()
            .single { it.email == "dan@example.com" }
            .firstConnectedAt
        assertNotNull(firstConnectedAt)

        Thread.sleep(2)
        repository.setOffline(name = "Dan", email = "dan@example.com")
        repository.setOnline(name = "Dan", email = "dan@example.com")
        repository.incrementMonthlyUsageCounter(name = "Dan", email = "dan@example.com")

        val after = repository
            .getUsersFirstConnection()
            .single { it.email == "dan@example.com" }
            .firstConnectedAt
        assertEquals(firstConnectedAt, after, "usageStartedAt must be immutable after first insert")
    }

    @Test
    fun `getUsersFirstConnection reports usageStartedAt as firstConnectedAt`() {
        repository.setOnline(name = "Eve", email = "eve@example.com")

        val firsts = repository.getUsersFirstConnection()
        val eve = firsts.single { it.email == "eve@example.com" }
        assertNotNull(eve.firstConnectedAt)
    }
}
