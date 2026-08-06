package com.bm.backend.services

import com.bm.backend.firebase.FirebaseAdminFactory
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

class FirebaseUserAccountRevoker : UserAccountRevoker {

    private val logger = LoggerFactory.getLogger(FirebaseUserAccountRevoker::class.java)

    override suspend fun revokeRefreshTokens(uid: String) {
        withContext(Dispatchers.IO) {
            runCatching {
                FirebaseAdminFactory.init()
                FirebaseAuth.getInstance().revokeRefreshTokens(uid)
            }.onSuccess {
                logger.info("AUDIT: Firebase refresh tokens revoked for uid={}", uid)
            }.onFailure { error ->
                logger.error(
                    "Failed to revoke Firebase refresh tokens for uid={}: {}",
                    uid,
                    error.message,
                    error
                )
            }
        }
    }
}
