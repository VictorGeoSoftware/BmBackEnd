package com.bm.backend.repositories

import com.bm.backend.repositories.ports.DatabaseHealthPort
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory

/**
 * Exposed-backed [DatabaseHealthPort].
 *
 * Issues the cheapest query the driver will accept and treats any failure as
 * "unreachable".
 */
class ExposedDatabaseHealthCheck : DatabaseHealthPort {

    private val log = LoggerFactory.getLogger(ExposedDatabaseHealthCheck::class.java)

    override fun isReachable(): Boolean = try {
        transaction { exec("SELECT 1") { it.next() } }
        true
    } catch (e: Exception) {
        // The previous implementation swallowed this with `catch (_: Exception)`,
        // so a database outage surfaced only as a bare 503 with nothing in the
        // logs to say why. Log it — this is exactly the kind of failure the
        // readiness probe exists to explain.
        log.warn("Database readiness check failed: {}", e.message, e)
        false
    }
}
