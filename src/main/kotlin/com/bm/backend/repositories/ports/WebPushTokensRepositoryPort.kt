package com.bm.backend.repositories.ports

/**
 * Port (Clean Architecture) for web push (FCM web) token persistence.
 *
 * Implementations must guarantee:
 * - One row per token; [upsert] reassigns an existing token to the new owner.
 * - [deleteByTokenForUid] only removes the token when it belongs to [uid].
 * - [deleteByEmail] compares emails case-insensitively.
 */
interface WebPushTokensRepositoryPort {

    fun upsert(token: String, uid: String, email: String, platform: String)

    /** Returns the number of rows deleted (0 or 1). */
    fun deleteByTokenForUid(token: String, uid: String): Int

    /** Returns the number of rows deleted. */
    fun deleteByEmail(email: String): Int
}
