package com.bm.backend.repositories

import com.bm.backend.database.UserActivityDb
import com.bm.backend.models.UserActivityFirstConnectionResponse
import com.bm.backend.models.UserActivityUserResponse
import com.bm.backend.repositories.ports.UserActivityRepositoryPort
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.lowerCase
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Instant
import java.time.YearMonth

class UserActivityRepository : UserActivityRepositoryPort {

    override fun setOnline(name: String, email: String) {
        transaction {
            val now = Instant.now()
            val monthKey = currentMonthKey()

            exec(
                """
                INSERT INTO user_activity (email, name, is_online, monthly_usage_count, month_key,
                    usage_started_at, last_connected_at, last_disconnected_at, updated_at)
                VALUES (?, ?, true, 0, ?, ?, ?, NULL, ?)
                ON CONFLICT (email) DO UPDATE SET
                    name = EXCLUDED.name,
                    is_online = true,
                    monthly_usage_count = CASE
                        WHEN user_activity.month_key = EXCLUDED.month_key THEN user_activity.monthly_usage_count
                        ELSE 0
                    END,
                    month_key = EXCLUDED.month_key,
                    last_connected_at = EXCLUDED.last_connected_at,
                    updated_at = EXCLUDED.updated_at
                """.trimIndent(),
                args = listOf(
                    UserActivityDb.email.columnType to email,
                    UserActivityDb.name.columnType to name,
                    UserActivityDb.monthKey.columnType to monthKey,
                    UserActivityDb.usageStartedAt.columnType to now,
                    UserActivityDb.lastConnectedAt.columnType to now,
                    UserActivityDb.updatedAt.columnType to now
                )
            )
        }
    }

    override fun setOffline(name: String, email: String) {
        transaction {
            val now = Instant.now()
            val monthKey = currentMonthKey()

            exec(
                """
                INSERT INTO user_activity (email, name, is_online, monthly_usage_count, month_key,
                    usage_started_at, last_connected_at, last_disconnected_at, updated_at)
                VALUES (?, ?, false, 0, ?, ?, NULL, ?, ?)
                ON CONFLICT (email) DO UPDATE SET
                    name = EXCLUDED.name,
                    is_online = false,
                    monthly_usage_count = CASE
                        WHEN user_activity.month_key = EXCLUDED.month_key THEN user_activity.monthly_usage_count
                        ELSE 0
                    END,
                    month_key = EXCLUDED.month_key,
                    last_disconnected_at = EXCLUDED.last_disconnected_at,
                    updated_at = EXCLUDED.updated_at
                """.trimIndent(),
                args = listOf(
                    UserActivityDb.email.columnType to email,
                    UserActivityDb.name.columnType to name,
                    UserActivityDb.monthKey.columnType to monthKey,
                    UserActivityDb.usageStartedAt.columnType to now,
                    UserActivityDb.lastDisconnectedAt.columnType to now,
                    UserActivityDb.updatedAt.columnType to now
                )
            )
        }
    }

    override fun incrementMonthlyUsageCounter(name: String, email: String) {
        transaction {
            val now = Instant.now()
            val monthKey = currentMonthKey()

            exec(
                """
                INSERT INTO user_activity (email, name, is_online, monthly_usage_count, month_key,
                    usage_started_at, last_connected_at, last_disconnected_at, updated_at)
                VALUES (?, ?, true, 1, ?, ?, ?, NULL, ?)
                ON CONFLICT (email) DO UPDATE SET
                    name = EXCLUDED.name,
                    monthly_usage_count = CASE
                        WHEN user_activity.month_key = EXCLUDED.month_key THEN user_activity.monthly_usage_count + 1
                        ELSE 1
                    END,
                    month_key = EXCLUDED.month_key,
                    updated_at = EXCLUDED.updated_at
                """.trimIndent(),
                args = listOf(
                    UserActivityDb.email.columnType to email,
                    UserActivityDb.name.columnType to name,
                    UserActivityDb.monthKey.columnType to monthKey,
                    UserActivityDb.usageStartedAt.columnType to now,
                    UserActivityDb.lastConnectedAt.columnType to now,
                    UserActivityDb.updatedAt.columnType to now
                )
            )
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
                        lastConnectedAt = row[UserActivityDb.lastConnectedAt]?.toEpochMilli(),
                        lastDisconnectedAt = row[UserActivityDb.lastDisconnectedAt]?.toEpochMilli(),
                        updatedAt = row[UserActivityDb.updatedAt].toEpochMilli()
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
                        firstConnectedAt = row[UserActivityDb.usageStartedAt]?.toEpochMilli()
                    )
                }
        }
    }

    override fun deleteByEmail(email: String): Int = transaction {
        val target = email.trim().lowercase()
        val condition = with(SqlExpressionBuilder) { UserActivityDb.email.lowerCase() eq target }
        UserActivityDb.deleteWhere { condition }
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
