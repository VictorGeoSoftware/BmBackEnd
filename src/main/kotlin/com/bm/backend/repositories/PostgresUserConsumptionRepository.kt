package com.bm.backend.repositories

import com.bm.backend.database.UserConsumptionDb
import com.bm.backend.models.UserConsumption
import com.bm.backend.repositories.ports.UserConsumptionRepositoryPort
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Instant

/**
 * Postgres-backed implementation of [UserConsumptionRepositoryPort].
 *
 * Stores the full [UserConsumption] payload as JSONB, keyed by a well-known
 * sentinel UID (`__global__`) since the current API is not per-user.
 * The table schema supports per-user storage for a future migration.
 */
class PostgresUserConsumptionRepository : UserConsumptionRepositoryPort {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    companion object {
        private const val GLOBAL_UID = "__global__"
    }

    override fun storeConsumptionData(consumptionReport: UserConsumption) {
        transaction {
            val now = Instant.now()
            val payload = json.encodeToString(consumptionReport)

            exec(
                """
                INSERT INTO user_consumption (uid, data, created_at, updated_at)
                VALUES (?, ?::jsonb, ?, ?)
                ON CONFLICT (uid) DO UPDATE SET
                    data = EXCLUDED.data,
                    updated_at = EXCLUDED.updated_at
                """.trimIndent(),
                args = listOf(
                    UserConsumptionDb.uid.columnType to GLOBAL_UID,
                    UserConsumptionDb.data.columnType to payload,
                    UserConsumptionDb.createdAt.columnType to now,
                    UserConsumptionDb.updatedAt.columnType to now
                )
            )
        }
    }

    override fun getConsumptionReport(): UserConsumption? {
        return transaction {
            UserConsumptionDb
                .selectAll()
                .where { UserConsumptionDb.uid eq GLOBAL_UID }
                .singleOrNull()
                ?.let { row ->
                    json.decodeFromString<UserConsumption>(row[UserConsumptionDb.data])
                }
        }
    }
}
