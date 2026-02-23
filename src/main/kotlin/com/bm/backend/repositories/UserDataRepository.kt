package com.bm.backend.repositories

import com.bm.backend.database.UserDataDb
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

class UserDataRepository {
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
            val existing = UserDataDb
                .selectAll()
                .where { UserDataDb.uid eq uid }
                .singleOrNull()

            if (existing == null) {
                UserDataDb.insert {
                    it[UserDataDb.uid] = uid
                    it[UserDataDb.email] = email
                    it[UserDataDb.displayName] = displayName
                    it[UserDataDb.photoURL] = photoURL
                    it[UserDataDb.providerIds] = providerIdsSerialized
                    it[UserDataDb.tokenIssuedAt] = tokenIssuedAt
                    it[UserDataDb.tokenExpiresAt] = tokenExpiresAt
                    it[UserDataDb.lastLoginAt] = now
                    it[UserDataDb.createdAt] = now
                    it[UserDataDb.updatedAt] = now
                }
            } else {
                UserDataDb.update({ UserDataDb.uid eq uid }) {
                    it[UserDataDb.email] = email
                    it[UserDataDb.displayName] = displayName
                    it[UserDataDb.photoURL] = photoURL
                    it[UserDataDb.providerIds] = providerIdsSerialized
                    it[UserDataDb.tokenIssuedAt] = tokenIssuedAt
                    it[UserDataDb.tokenExpiresAt] = tokenExpiresAt
                    it[UserDataDb.lastLoginAt] = now
                    it[UserDataDb.updatedAt] = now
                }
            }
        }
    }
}
