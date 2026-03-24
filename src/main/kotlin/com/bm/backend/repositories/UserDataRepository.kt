package com.bm.backend.repositories

import com.bm.backend.database.UserDataDb
import com.bm.backend.security.EncryptionUtils
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.slf4j.LoggerFactory

class UserDataRepository {

    private val logger = LoggerFactory.getLogger(UserDataRepository::class.java)

    fun upsertUserData(
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

            // Encrypt PII fields before storing
            val encryptedEmail = EncryptionUtils.encryptNullable(email)
            val encryptedDisplayName = EncryptionUtils.encryptNullable(displayName)
            val encryptedPhotoURL = EncryptionUtils.encryptNullable(photoURL)

            val existing = UserDataDb
                .selectAll()
                .where { UserDataDb.uid eq uid }
                .singleOrNull()

            if (existing == null) {
                UserDataDb.insert {
                    it[UserDataDb.uid] = uid
                    it[UserDataDb.email] = encryptedEmail
                    it[UserDataDb.displayName] = encryptedDisplayName
                    it[UserDataDb.photoURL] = encryptedPhotoURL
                    it[UserDataDb.providerIds] = providerIdsSerialized
                    it[UserDataDb.tokenIssuedAt] = tokenIssuedAt
                    it[UserDataDb.tokenExpiresAt] = tokenExpiresAt
                    it[UserDataDb.lastLoginAt] = now
                    it[UserDataDb.createdAt] = now
                    it[UserDataDb.updatedAt] = now
                }
                logger.info("AUDIT: New user data created for uid={}", uid)
            } else {
                UserDataDb.update({ UserDataDb.uid eq uid }) {
                    it[UserDataDb.email] = encryptedEmail
                    it[UserDataDb.displayName] = encryptedDisplayName
                    it[UserDataDb.photoURL] = encryptedPhotoURL
                    it[UserDataDb.providerIds] = providerIdsSerialized
                    it[UserDataDb.tokenIssuedAt] = tokenIssuedAt
                    it[UserDataDb.tokenExpiresAt] = tokenExpiresAt
                    it[UserDataDb.lastLoginAt] = now
                    it[UserDataDb.updatedAt] = now
                }
                logger.info("AUDIT: User data updated for uid={}", uid)
            }
        }
    }
}
