package com.bm.backend.repositories

import com.bm.backend.database.AdminUsersDb
import com.bm.backend.repositories.ports.AdminUsersRepositoryPort
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

class AdminUsersRepository : AdminUsersRepositoryPort {

    override fun existsByEmail(email: String): Boolean = transaction {
        AdminUsersDb
            .selectAll()
            .where { AdminUsersDb.email eq email }
            .limit(1)
            .any()
    }
}
