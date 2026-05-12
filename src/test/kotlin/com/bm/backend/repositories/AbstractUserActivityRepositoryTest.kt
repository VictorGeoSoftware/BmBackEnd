package com.bm.backend.repositories

import com.bm.backend.database.UserActivityDb
import com.bm.backend.models.UserActivityFirstConnectionResponse
import com.bm.backend.models.UserActivityUserResponse
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.YearMonth
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Abstract contract tests for [UserActivityRepository].
 * Subclasses provide DB setup; tests run identically on SQLite and Postgres.
 */
abstract class AbstractUserActivityRepositoryTest {

    protected lateinit var repository: UserActivityRepository

    abstract fun initDatabase()

    @BeforeEach
    fun setUp() {
        initDatabase()
        transaction { UserActivityDb.deleteAll() }
        repository = UserActivityRepository()
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
        val bob = repository.getUsersActivity().single { it.email == "bob@example.com" }
        assertEquals(3, bob.monthlyUsageCount)
    }

    @Test
    fun `monthlyUsageCount resets when month changes`() {
        val previousMonth = YearMonth.now().minusMonths(1).toString()
        val pastInstant = Instant.now().minusSeconds(35L * 24 * 3_600)
        transaction {
            UserActivityDb.insert {
                it[UserActivityDb.name] = "Carol"
                it[UserActivityDb.email] = "carol@example.com"
                it[UserActivityDb.isOnline] = false
                it[UserActivityDb.monthlyUsageCount] = 7
                it[UserActivityDb.monthKey] = previousMonth
                it[UserActivityDb.usageStartedAt] = pastInstant
                it[UserActivityDb.lastConnectedAt] = pastInstant
                it[UserActivityDb.lastDisconnectedAt] = pastInstant
                it[UserActivityDb.updatedAt] = pastInstant
            }
        }
        val carol = repository.getUsersActivity().single { it.email == "carol@example.com" }
        assertEquals(0, carol.monthlyUsageCount)
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
        val eve = repository.getUsersFirstConnection().single { it.email == "eve@example.com" }
        assertNotNull(eve.firstConnectedAt)
    }
}
