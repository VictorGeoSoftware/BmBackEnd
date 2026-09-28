package com.bm.backend.repositories

import com.bm.backend.database.GrantedUsersDb
import com.bm.backend.models.GrantedUser
import com.bm.backend.models.UserTier
import com.bm.backend.repositories.ports.GrantedUsersRepositoryPort
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.Instant

class GrantedUsersRepository : GrantedUsersRepositoryPort {

    override fun findByEmail(email: String): GrantedUser? = transaction {
        GrantedUsersDb
            .selectAll()
            .where { GrantedUsersDb.email eq email }
            .limit(1)
            .map(::toDomainModel)
            .singleOrNull()
    }

    override fun insert(email: String, tier: UserTier): Boolean = transaction {
        val alreadyGranted = GrantedUsersDb
            .selectAll()
            .where { GrantedUsersDb.email eq email }
            .limit(1)
            .any()
        if (alreadyGranted) return@transaction false

        GrantedUsersDb.insert {
            it[GrantedUsersDb.email] = email
            it[GrantedUsersDb.tier] = tier
            it[createdAt] = Instant.now()
        }
        true
    }

    override fun updateTier(email: String, tier: UserTier): Int = transaction {
        GrantedUsersDb.update({ GrantedUsersDb.email eq email }) {
            it[GrantedUsersDb.tier] = tier
        }
    }

    override fun deleteByEmail(email: String): Int = transaction {
        val target = email
        val condition = with(SqlExpressionBuilder) { GrantedUsersDb.email eq target }
        GrantedUsersDb.deleteWhere { condition }
    }

    override fun findAll(): List<GrantedUser> = transaction {
        GrantedUsersDb
            .selectAll()
            .orderBy(GrantedUsersDb.createdAt to SortOrder.DESC)
            .map(::toDomainModel)
    }

    private fun toDomainModel(row: org.jetbrains.exposed.sql.ResultRow) = GrantedUser(
        email = row[GrantedUsersDb.email],
        tier = row[GrantedUsersDb.tier],
        createdAt = row[GrantedUsersDb.createdAt]
    )
}
