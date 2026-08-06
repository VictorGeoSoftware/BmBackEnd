package com.bm.backend.services

/**
 * Port for notifying app clients that an account's access has been revoked,
 * so an active session signs out immediately instead of at the next sync.
 */
interface ForceLogoutNotifier {

    /**
     * Broadcasts a force-logout signal for [email]. Best-effort: failures are
     * logged by implementations, never thrown.
     */
    suspend fun notifyForceLogout(email: String)
}
