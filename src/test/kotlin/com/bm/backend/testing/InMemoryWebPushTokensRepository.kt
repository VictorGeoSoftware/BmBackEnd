package com.bm.backend.testing

import com.bm.backend.repositories.ports.WebPushTokensRepositoryPort
import java.util.concurrent.ConcurrentHashMap

/** In-memory test double for [WebPushTokensRepositoryPort]. */
class InMemoryWebPushTokensRepository : WebPushTokensRepositoryPort {

    data class Row(val token: String, val uid: String, val email: String, val platform: String)

    private val rows = ConcurrentHashMap<String, Row>()

    fun all(): List<Row> = rows.values.toList()

    fun find(token: String): Row? = rows[token]

    override fun upsert(token: String, uid: String, email: String, platform: String) {
        rows[token] = Row(token, uid, email, platform)
    }

    override fun deleteByTokenForUid(token: String, uid: String): Int {
        val row = rows[token] ?: return 0
        if (row.uid != uid) return 0
        rows.remove(token)
        return 1
    }

    override fun deleteByEmail(email: String): Int {
        val target = email.trim().lowercase()
        val keys = rows.values.filter { it.email.trim().lowercase() == target }.map { it.token }
        keys.forEach(rows::remove)
        return keys.size
    }

    override fun allTokens(): List<String> = rows.keys.toList()

    override fun deleteByTokens(tokens: Collection<String>): Int =
        tokens.toSet().count { rows.remove(it) != null }
}
