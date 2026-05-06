package com.bm.backend.repositories

import com.bm.backend.database.UserActivityDb
import com.bm.backend.models.UserActivityFirstConnectionResponse
import com.bm.backend.models.UserActivityUserResponse
import com.bm.backend.repositories.ports.UserActivityRepositoryPort
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.YearMonth

class UserActivityRepository : UserActivityRepositoryPort {

    override fun setOnline(name: String, email: String) {
        transaction {
            val now = System.currentTimeMillis()
            val monthKey = currentMonthKey()
            val existing = findByEmail(email)

            if (existing == null) {
                UserActivityDb.insert {
                    it[UserActivityDb.name] = name
                    it[UserActivityDb.email] = email
                    it[UserActivityDb.isOnline] = true
                    it[UserActivityDb.monthlyUsageCount] = 0
                    it[UserActivityDb.monthKey] = monthKey
                    it[UserActivityDb.usageStartedAt] = now
                    it[UserActivityDb.lastConnectedAt] = now
                    it[UserActivityDb.lastDisconnectedAt] = null
                    it[UserActivityDb.updatedAt] = now
                }
            } else {
                val monthlyUsageCount = resolveMonthlyCount(existing, monthKey)
                UserActivityDb.update({ UserActivityDb.email eq email }) {
                    it[UserActivityDb.name] = name
                    it[UserActivityDb.isOnline] = true
                    it[UserActivityDb.monthlyUsageCount] = monthlyUsageCount
                    it[UserActivityDb.monthKey] = monthKey
                    it[UserActivityDb.lastConnectedAt] = now
                    it[UserActivityDb.updatedAt] = now
                }
            }
        }
    }

    override fun setOffline(name: String, email: String) {
        transaction {
            val now = System.currentTimeMillis()
            val monthKey = currentMonthKey()
            val existing = findByEmail(email)

            if (existing == null) {
                UserActivityDb.insert {
                    it[UserActivityDb.name] = name
                    it[UserActivityDb.email] = email
                    it[UserActivityDb.isOnline] = false
                    it[UserActivityDb.monthlyUsageCount] = 0
                    it[UserActivityDb.monthKey] = monthKey
                    it[UserActivityDb.usageStartedAt] = now
                    it[UserActivityDb.lastConnectedAt] = null
                    it[UserActivityDb.lastDisconnectedAt] = now
                    it[UserActivityDb.updatedAt] = now
                }
            } else {
                val monthlyUsageCount = resolveMonthlyCount(existing, monthKey)
                UserActivityDb.update({ UserActivityDb.email eq email }) {
                    it[UserActivityDb.name] = name
                    it[UserActivityDb.isOnline] = false
                    it[UserActivityDb.monthlyUsageCount] = monthlyUsageCount
                    it[UserActivityDb.monthKey] = monthKey
                    it[UserActivityDb.lastDisconnectedAt] = now
                    it[UserActivityDb.updatedAt] = now
                }
            }
        }
    }

    override fun incrementMonthlyUsageCounter(name: String, email: String) {
        transaction {
            val now = System.currentTimeMillis()
            val monthKey = currentMonthKey()
            val existing = findByEmail(email)

            if (existing == null) {
                UserActivityDb.insert {
                    it[UserActivityDb.name] = name
                    it[UserActivityDb.email] = email
                    it[UserActivityDb.isOnline] = true
                    it[UserActivityDb.monthlyUsageCount] = 1
                    it[UserActivityDb.monthKey] = monthKey
                    it[UserActivityDb.usageStartedAt] = now
                    it[UserActivityDb.lastConnectedAt] = now
                    it[UserActivityDb.lastDisconnectedAt] = null
                    it[UserActivityDb.updatedAt] = now
                }
            } else {
                val monthlyUsageCount = resolveMonthlyCount(existing, monthKey) + 1
                UserActivityDb.update({ UserActivityDb.email eq email }) {
                    it[UserActivityDb.name] = name
                    it[UserActivityDb.monthlyUsageCount] = monthlyUsageCount
                    it[UserActivityDb.monthKey] = monthKey
                    it[UserActivityDb.updatedAt] = now
                }
            }
        }
    }

    override fun getUsersActivity(): List<UserActivityUserResponse> {
        return transaction {
            val monthKey = currentMonthKey()
            UserActivityDb
                .selectAll()
                .map { row ->
                    val monthlyUsageCount = resolveMonthlyCount(row, monthKey)
                    UserActivityUserResponse(
                        name = row[UserActivityDb.name],
                        email = row[UserActivityDb.email],
                        isOnline = row[UserActivityDb.isOnline],
                        monthlyUsageCount = monthlyUsageCount,
                        lastConnectedAt = row[UserActivityDb.lastConnectedAt],
                        lastDisconnectedAt = row[UserActivityDb.lastDisconnectedAt],
                        updatedAt = row[UserActivityDb.updatedAt]
                    )
                }
                .sortedByDescending { user -> user.updatedAt }
        }
    }

    override fun getUsersFirstConnection(): List<UserActivityFirstConnectionResponse> {
        return transaction {
            UserActivityDb
                .selectAll()
                .map { row ->
                    UserActivityFirstConnectionResponse(
                        email = row[UserActivityDb.email],
                        firstConnectedAt = row[UserActivityDb.usageStartedAt]
                    )
                }
        }
    }

    private fun findByEmail(email: String): ResultRow? {
        return UserActivityDb
            .selectAll()
            .where { UserActivityDb.email eq email }
            .singleOrNull()
    }

    private fun resolveMonthlyCount(row: ResultRow, currentMonthKey: String): Int {
        val storedMonthKey = row[UserActivityDb.monthKey]
        if (storedMonthKey != currentMonthKey) {
            return 0
        }
        return row[UserActivityDb.monthlyUsageCount]
    }

    private fun currentMonthKey(): String {
        return YearMonth.now().toString()
    }
}
