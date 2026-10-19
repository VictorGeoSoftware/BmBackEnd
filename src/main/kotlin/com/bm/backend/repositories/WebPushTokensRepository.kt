package com.bm.backend.repositories

import com.bm.backend.database.WebPushTokensDb
import com.bm.backend.repositories.ports.WebPushTokensRepositoryPort
import org.jetbrains.exposed.sql.SqlExpressionBuilder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.lowerCase
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.upsert
import java.time.Instant

class WebPushTokensRepository : WebPushTokensRepositoryPort {

    override fun upsert(token: String, uid: String, email: String, platform: String) {
        transaction {
            val now = Instant.now()
            WebPushTokensDb.upsert(
                WebPushTokensDb.token,
                onUpdateExclude = listOf(WebPushTokensDb.createdAt)
            ) {
                it[WebPushTokensDb.token] = token
                it[WebPushTokensDb.uid] = uid
                it[WebPushTokensDb.email] = email
                it[WebPushTokensDb.platform] = platform
                it[createdAt] = now
                it[updatedAt] = now
            }
        }
    }

    override fun deleteByTokenForUid(token: String, uid: String): Int = transaction {
        val condition = with(SqlExpressionBuilder) {
            (WebPushTokensDb.token eq token) and (WebPushTokensDb.uid eq uid)
        }
        WebPushTokensDb.deleteWhere { condition }
    }

    override fun deleteByEmail(email: String): Int = transaction {
        val target = email.trim().lowercase()
        val condition = with(SqlExpressionBuilder) { WebPushTokensDb.email.lowerCase() eq target }
        WebPushTokensDb.deleteWhere { condition }
    }

    override fun allTokens(): List<String> = transaction {
        WebPushTokensDb.selectAll().map { it[WebPushTokensDb.token] }
    }

    override fun deleteByTokens(tokens: Collection<String>): Int {
        if (tokens.isEmpty()) return 0
        return transaction {
            val condition = with(SqlExpressionBuilder) { WebPushTokensDb.token inList tokens.toSet() }
            WebPushTokensDb.deleteWhere { condition }
        }
    }
}
