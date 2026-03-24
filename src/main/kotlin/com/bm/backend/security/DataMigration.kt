package com.bm.backend.security

import com.bm.backend.database.UserDataDb
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.slf4j.LoggerFactory

object DataMigration {

    private val logger = LoggerFactory.getLogger(DataMigration::class.java)

    fun encryptExistingUserData() {
        if (!EncryptionUtils.isInitialized()) {
            logger.error("Cannot migrate: EncryptionUtils not initialized")
            return
        }

        transaction {
            val rows = UserDataDb.selectAll().toList()
            var migrated = 0
            var skipped = 0

            rows.forEach { row ->
                val uid = row[UserDataDb.uid]
                val email = row[UserDataDb.email]
                val displayName = row[UserDataDb.displayName]
                val photoURL = row[UserDataDb.photoURL]

                // Skip if already encrypted (encrypted data is Base64 and much longer)
                if (email != null && isLikelyEncrypted(email)) {
                    skipped++
                    return@forEach
                }

                UserDataDb.update({ UserDataDb.uid eq uid }) {
                    it[UserDataDb.email] = EncryptionUtils.encryptNullable(email)
                    it[UserDataDb.displayName] = EncryptionUtils.encryptNullable(displayName)
                    it[UserDataDb.photoURL] = EncryptionUtils.encryptNullable(photoURL)
                }
                migrated++
            }

            logger.info("AUDIT: Data migration completed — migrated={}, skipped={}", migrated, skipped)
        }
    }

    private fun isLikelyEncrypted(value: String): Boolean {
        // Encrypted values are Base64 and at least ~40 chars (12-byte IV + ciphertext + 16-byte tag)
        // A plain email like "user@example.com" would not match this pattern
        if (value.length < 40) return false
        return try {
            val decoded = java.util.Base64.getDecoder().decode(value)
            decoded.size > 28 // IV (12) + at least 1 byte ciphertext + tag (16)
        } catch (e: Exception) {
            false
        }
    }
}
