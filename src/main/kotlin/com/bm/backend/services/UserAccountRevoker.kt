package com.bm.backend.services

/**
 * Port for revoking a user's authentication sessions, forcing every signed-in
 * client to re-authenticate. Combined with a removed access grant this makes a
 * deleted user unable to continue an existing session.
 */
interface UserAccountRevoker {

    /**
     * Revokes all refresh tokens for [uid]. Best-effort: failures are logged
     * by implementations, never thrown.
     */
    suspend fun revokeRefreshTokens(uid: String)
}
