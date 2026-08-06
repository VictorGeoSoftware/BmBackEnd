package com.bm.backend.repositories

import com.bm.backend.database.GrantedUsersDb
import com.bm.backend.models.GrantedUser
import com.bm.backend.repositories.ports.GrantedUsersRepositoryPort
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Instant

class GrantedUsersRepository : GrantedUsersRepositoryPort {

    override fun existsByEmail(email: String): Boolean = transaction {
        GrantedUsersDb
            .selectAll()
            .where { GrantedUsersDb.email eq email }
            .limit(1)
            .any()
    }

    override fun insert(email: String): Boolean = transaction {
        val alreadyGranted = GrantedUsersDb
            .selectAll()
            .where { GrantedUsersDb.email eq email }
            .limit(1)
            .any()
        if (alreadyGranted) return@transaction false

        GrantedUsersDb.insert {
            it[GrantedUsersDb.email] = email
            it[createdAt] = Instant.now()
        }
        true
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
            .map { row ->
                GrantedUser(
                    email = row[GrantedUsersDb.email],
                    createdAt = row[GrantedUsersDb.createdAt]
                )
            }
    }
}
