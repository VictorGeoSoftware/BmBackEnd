package com.bm.backend.services

/** Sends one data-only FCM web push to many tokens. See docs/WEB_PUSH.md. */
interface WebPushSender {
    suspend fun sendMulticast(tokens: List<String>, data: Map<String, String>, ttlSeconds: Long): List<SendOutcome>

    /** [errorCode] is null on success, otherwise the FCM `MessagingErrorCode` name (or "UNKNOWN"). */
    data class SendOutcome(val token: String, val errorCode: String?)
}
