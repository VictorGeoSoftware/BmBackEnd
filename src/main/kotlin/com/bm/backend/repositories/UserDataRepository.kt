package com.bm.backend.repositories

import com.bm.backend.database.UserDataDb
import com.bm.backend.repositories.ports.UserDataRepositoryPort
import com.bm.backend.security.EncryptionUtils
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory

class UserDataRepository : UserDataRepositoryPort {

    private val logger = LoggerFactory.getLogger(UserDataRepository::class.java)

    override fun upsertUserData(
        uid: String,
        email: String?,
        displayName: String?,
        photoURL: String?,
        providerIds: List<String>,
        tokenIssuedAt: Long,
        tokenExpiresAt: Long
    ) {
        transaction {
            val now = System.currentTimeMillis()
            val providerIdsSerialized = providerIds.joinToString(",")

            val encryptedEmail = EncryptionUtils.encryptNullable(email)
            val encryptedDisplayName = EncryptionUtils.encryptNullable(displayName)
            val encryptedPhotoURL = EncryptionUtils.encryptNullable(photoURL)

            exec(
                """
                INSERT INTO user_data (uid, email, display_name, photo_url, provider_ids,
                    token_issued_at, token_expires_at, last_login_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (uid) DO UPDATE SET
                    email = EXCLUDED.email,
                    display_name = EXCLUDED.display_name,
                    photo_url = EXCLUDED.photo_url,
                    provider_ids = EXCLUDED.provider_ids,
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
}
