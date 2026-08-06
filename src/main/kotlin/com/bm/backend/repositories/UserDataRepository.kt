package com.bm.backend.repositories

import com.bm.backend.database.UserDataDb
import com.bm.backend.repositories.ports.UserDataRepositoryPort
import com.bm.backend.security.EncryptionUtils
import org.jetbrains.exposed.sql.SqlExpressionBuilder
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.slf4j.LoggerFactory
import java.time.Instant

class UserDataRepository : UserDataRepositoryPort {

    private val logger = LoggerFactory.getLogger(UserDataRepository::class.java)

    override fun upsertUserData(
        uid: String,
        email: String?,
        displayName: String?,
        photoURL: String?,
        providerIds: List<String>,
        tokenIssuedAt: Instant,
        tokenExpiresAt: Instant,
        phoneUuid: String?
    ) {
        transaction {
            val now = Instant.now()
            val providerIdsSerialized = providerIds.joinToString(",")

            val encryptedEmail = EncryptionUtils.encryptNullable(email)
            val encryptedDisplayName = EncryptionUtils.encryptNullable(displayName)
            val encryptedPhotoURL = EncryptionUtils.encryptNullable(photoURL)

            exec(
                """
                INSERT INTO user_data (uid, email, display_name, photo_url, provider_ids, phone_uuid,
                    token_issued_at, token_expires_at, last_login_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (uid) DO UPDATE SET
                    email = EXCLUDED.email,
                    display_name = EXCLUDED.display_name,
                    photo_url = EXCLUDED.photo_url,
                    provider_ids = EXCLUDED.provider_ids,
                    phone_uuid = COALESCE(user_data.phone_uuid, EXCLUDED.phone_uuid),
                    token_issued_at = EXCLUDED.token_issued_at,
                    token_expires_at = EXCLUDED.token_expires_at,
                    last_login_at = EXCLUDED.last_login_at,
                    updated_at = EXCLUDED.updated_at
                """.trimIndent(),
                args = listOf(
                    UserDataDb.uid.columnType to uid,
                    UserDataDb.email.columnType to encryptedEmail,
                    UserDataDb.displayName.columnType to encryptedDisplayName,
                    UserDataDb.photoURL.columnType to encryptedPhotoURL,
                    UserDataDb.providerIds.columnType to providerIdsSerialized,
                    UserDataDb.phoneUuid.columnType to phoneUuid,
                    UserDataDb.tokenIssuedAt.columnType to tokenIssuedAt,
                    UserDataDb.tokenExpiresAt.columnType to tokenExpiresAt,
                    UserDataDb.lastLoginAt.columnType to now,
                    UserDataDb.createdAt.columnType to now,
                    UserDataDb.updatedAt.columnType to now
                )
            )

            logger.info("AUDIT: User data upserted for uid={}", uid)
        }
    }

    override fun findPhoneUuid(uid: String): String? = transaction {
        UserDataDb
            .selectAll()
            .where { UserDataDb.uid eq uid }
            .limit(1)
            .firstOrNull()
            ?.get(UserDataDb.phoneUuid)
    }

    override fun clearPhoneUuidByEmail(email: String): Int = transaction {
        val matchingIds = findRowIdsByEmail(email)

        if (matchingIds.isEmpty()) {
            logger.info("AUDIT: Device binding reset requested but no account matched")
            return@transaction 0
        }

        val now = Instant.now()
        val updated = UserDataDb.update({ UserDataDb.id inList matchingIds }) {
            it[UserDataDb.phoneUuid] = null
            it[UserDataDb.updatedAt] = now
        }
        logger.info("AUDIT: Device binding reset for {} account(s)", updated)
        updated
    }

    override fun findUidByEmail(email: String): String? = transaction {
        val matchingIds = findRowIdsByEmail(email)
        if (matchingIds.isEmpty()) return@transaction null
        UserDataDb
            .selectAll()
            .where { UserDataDb.id inList matchingIds }
            .limit(1)
            .firstOrNull()
            ?.get(UserDataDb.uid)
    }

    override fun deleteByEmail(email: String): Int = transaction {
        val matchingIds = findRowIdsByEmail(email)
        if (matchingIds.isEmpty()) return@transaction 0
        val condition = with(SqlExpressionBuilder) { UserDataDb.id inList matchingIds }
        val deleted = UserDataDb.deleteWhere { condition }
        logger.info("AUDIT: User data deleted for {} account(s)", deleted)
        deleted
    }

    // `email` is encrypted at rest with a random IV, so it cannot be matched
    // with a SQL predicate; scan and compare decrypted values instead. The
    // user_data table holds one row per tester, so this stays inexpensive.
    // Must be called inside a transaction.
    private fun findRowIdsByEmail(email: String): List<Int> {
        val target = email.trim().lowercase()
        return UserDataDb
            .selectAll()
            .mapNotNull { row ->
                val encryptedEmail = row[UserDataDb.email] ?: return@mapNotNull null
                val decrypted = runCatching { EncryptionUtils.decrypt(encryptedEmail) }.getOrNull()
                if (decrypted?.trim()?.lowercase() == target) row[UserDataDb.id].value else null
            }
    }
}
