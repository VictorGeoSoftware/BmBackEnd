package com.bm.backend.testing

import com.bm.backend.models.UserActivityFirstConnectionResponse
import com.bm.backend.models.UserActivityUserResponse
import com.bm.backend.repositories.ports.UserActivityRepositoryPort
import java.time.YearMonth
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory test double for [UserActivityRepositoryPort].
 *
 * Mirrors the production contract without touching the database, so service
 * tests can run as fast unit tests.
 */
class InMemoryUserActivityRepository(
    private val clock: () -> Long = System::currentTimeMillis,
    private val monthKeyProvider: () -> String = { YearMonth.now().toString() }
) : UserActivityRepositoryPort {

    private data class Row(
        var name: String,
        val email: String,
        var isOnline: Boolean,
        var monthlyUsageCount: Int,
        var monthKey: String,
        val usageStartedAt: Long,
        var lastConnectedAt: Long?,
        var lastDisconnectedAt: Long?,
        var updatedAt: Long
    )

    private val rows = ConcurrentHashMap<String, Row>()

    override fun setOnline(name: String, email: String) {
        val now = clock()
        val mk = monthKeyProvider()
        rows.compute(email) { _, existing ->
            existing?.apply {
                this.name = name
                this.isOnline = true
                this.monthlyUsageCount = if (this.monthKey == mk) this.monthlyUsageCount else 0
                this.monthKey = mk
                this.lastConnectedAt = now
                this.updatedAt = now
            } ?: Row(
                name = name,
                email = email,
                isOnline = true,
                monthlyUsageCount = 0,
                monthKey = mk,
                usageStartedAt = now,
                lastConnectedAt = now,
                lastDisconnectedAt = null,
                updatedAt = now
            )
        }
    }

    override fun setOffline(name: String, email: String) {
        val now = clock()
        val mk = monthKeyProvider()
        rows.compute(email) { _, existing ->
            existing?.apply {
                this.name = name
                this.isOnline = false
                this.monthlyUsageCount = if (this.monthKey == mk) this.monthlyUsageCount else 0
                this.monthKey = mk
                this.lastDisconnectedAt = now
                this.updatedAt = now
            } ?: Row(
                name = name,
                email = email,
                isOnline = false,
                monthlyUsageCount = 0,
                monthKey = mk,
                usageStartedAt = now,
                lastConnectedAt = null,
                lastDisconnectedAt = now,
                updatedAt = now
            )
        }
    }

    override fun incrementMonthlyUsageCounter(name: String, email: String) {
        val now = clock()
        val mk = monthKeyProvider()
        rows.compute(email) { _, existing ->
            existing?.apply {
                this.name = name
                this.monthlyUsageCount = (if (this.monthKey == mk) this.monthlyUsageCount else 0) + 1
                this.monthKey = mk
                this.updatedAt = now
            } ?: Row(
                name = name,
                email = email,
                isOnline = true,
                monthlyUsageCount = 1,
                monthKey = mk,
                usageStartedAt = now,
                lastConnectedAt = now,
                lastDisconnectedAt = null,
                updatedAt = now
            )
        }
    }

    override fun getUsersActivity(): List<UserActivityUserResponse> {
        val mk = monthKeyProvider()
        return rows.values
            .map { r ->
                UserActivityUserResponse(
                    name = r.name,
                    email = r.email,
                    isOnline = r.isOnline,
                    monthlyUsageCount = if (r.monthKey == mk) r.monthlyUsageCount else 0,
                    lastConnectedAt = r.lastConnectedAt,
                    lastDisconnectedAt = r.lastDisconnectedAt,
                    updatedAt = r.updatedAt
                )
            }
            .sortedByDescending { it.updatedAt }
    }

    override fun getUsersFirstConnection(): List<UserActivityFirstConnectionResponse> {
        return rows.values.map {
            UserActivityFirstConnectionResponse(
                email = it.email,
                firstConnectedAt = it.usageStartedAt
            )
        }
    }

    override fun deleteByEmail(email: String): Int {
        val target = email.trim().lowercase()
        val key = rows.keys.firstOrNull { it.trim().lowercase() == target }
            ?: return 0
        rows.remove(key)
        return 1
    }
}
