package com.bm.backend.testing

import java.sql.ResultSet
import java.util.UUID

private const val columns = "deployment, supplier, ordinal, store, authority, target, environment, schema, revision, semantics, origin, digest, artifact"

private fun manifest(r: ResultSet): ProdAnalogRelease = ProdAnalogRelease(r.getString(8), r.getString(7),
    ProdAnalogBinding(r.getObject(4, UUID::class.java), r.getObject(5, UUID::class.java), r.getObject(6, UUID::class.java)),
    r.getString(2), r.getObject(1, UUID::class.java), r.getLong(3), r.getString(9), r.getString(10),
    r.getString(11), r.getString(12), r.getBytes(13))

internal class ProdAnalogReleaseReader(private val tx: ProdAnalogTransaction) {
    val binding get() = tx.binding
    fun verify() = tx.bounded { query("SELECT $columns FROM analog.release WHERE false") { check(!it.next()) } }
    fun head(supplier: String, remaining: () -> Long, checkpoint: () -> Unit) = tx.use(remaining, checkpoint) {
        // Never filter by digest, semantics or compatibility before choosing the head.
        query("SELECT $columns FROM analog.release WHERE supplier = ? ORDER BY ordinal DESC LIMIT 1", supplier) {
            if (it.next()) manifest(it) else null
        }
    }
}

internal class ProdAnalogReleaseWriter(private val tx: ProdAnalogTransaction,
    private val beforeCommit: () -> Unit, private val afterCommit: () -> Unit,
) {
    val binding get() = tx.binding
    fun verify() = tx.bounded { query("SELECT $columns FROM analog.release WHERE false") { check(!it.next()) } }

    /** False is observation of an exact already committed result, even if it is no longer the head. */
    fun commit(release: ProdAnalogRelease): Boolean = tx.bounded {
        query("SELECT pg_advisory_xact_lock(27071)") { check(it.next()) } // fixture has one releasable supplier
        val prior = query("SELECT $columns FROM analog.release WHERE deployment = ?", release.deployment) {
            if (it.next()) manifest(it) else null
        }
        if (prior != null) {
            if (!prior.same(release)) throw ProdAnalogUnavailable()
            return@bounded false
        }
        val ordinal = query("SELECT max(ordinal) FROM analog.release WHERE supplier = ?", release.supplier) { it.next(); it.getLong(1) }
        if (release.ordinal <= ordinal) throw ProdAnalogUnavailable()
        update("INSERT INTO analog.release ($columns) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            release.deployment, release.supplier, release.ordinal, release.binding.store, release.binding.authority,
            release.binding.destination, release.environment, release.schema, release.revision, release.semantics,
            release.origin, release.digest, release.bytes())
        beforeCommit()
        commit()
        afterCommit()
        true
    }
}
