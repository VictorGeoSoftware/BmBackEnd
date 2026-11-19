package com.bm.backend.testing

import java.sql.Connection
import java.sql.SQLException
import java.util.UUID

/** Provisioner-only handle; nothing in Netty receives credentials, owner access or another DB binding. */
internal class ProdAnalogPostgres(
    private val ownerConnect: (String, (Connection) -> Unit) -> Unit,
    private val connect: (String, String, String, () -> Long, () -> Unit) -> Connection,
) {
    val binding = ProdAnalogBinding(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())
    private val suffix = binding.store.toString().take(8)
    val database = "prod_analog_$suffix"
    enum class Role { DEPLOY, RESOLVE, IMPORT, LIST }
    private val passwords = Role.entries.associateWith { UUID.randomUUID().toString() }
    private fun user(role: Role) = "analog_${role.name.lowercase()}_$suffix"

    fun provision(admin: String) {
        ownerConnect(admin) { sql ->
            sql.exec("CREATE DATABASE $database")
            sql.exec("REVOKE ALL ON DATABASE $database FROM PUBLIC")
            for ((role, password) in passwords) {
                sql.exec("CREATE ROLE ${user(role)} LOGIN PASSWORD '$password'")
                sql.exec("GRANT CONNECT ON DATABASE $database TO ${user(role)}")
            }
        }
        owner { sql ->
            sql.exec("CREATE SCHEMA analog")
            sql.exec("""CREATE TABLE analog.identity (
                store uuid PRIMARY KEY, authority uuid NOT NULL, target uuid NOT NULL,
                environment text NOT NULL, schema_version integer NOT NULL)""")
            sql.prepareStatement("INSERT INTO analog.identity VALUES (?, ?, ?, ?, 1)").use {
                it.setObject(1, binding.store); it.setObject(2, binding.authority); it.setObject(3, binding.destination)
                it.setString(4, PROD_ANALOG); it.executeUpdate()
            }
            sql.exec("""CREATE TABLE analog.release (
                deployment uuid PRIMARY KEY, supplier text NOT NULL CHECK (supplier = 'gesternova'),
                ordinal bigint NOT NULL CHECK (ordinal > 0), store uuid NOT NULL REFERENCES analog.identity(store),
                authority uuid NOT NULL, target uuid NOT NULL, environment text NOT NULL CHECK (environment = 'prod-analog'),
                schema text NOT NULL CHECK (schema = 'bm.teach.prod-analog-release/v1'),
                revision text NOT NULL, semantics text NOT NULL, origin text NOT NULL, digest text NOT NULL,
                artifact bytea NOT NULL CHECK (octet_length(artifact) BETWEEN 1 AND 8192),
                registered_at timestamptz NOT NULL DEFAULT clock_timestamp(),
                UNIQUE(supplier, ordinal), UNIQUE(store, supplier, ordinal, revision, digest))""")
            sql.exec("""CREATE TABLE analog.batch (
                id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, actor uuid NOT NULL, attempt text NOT NULL,
                fingerprint text NOT NULL, store uuid NOT NULL, supplier text NOT NULL, ordinal bigint NOT NULL,
                revision text NOT NULL, digest text NOT NULL, imported_at timestamptz NOT NULL DEFAULT clock_timestamp(),
                UNIQUE(actor, attempt), UNIQUE(id, actor), FOREIGN KEY (store, supplier, ordinal, revision, digest)
                    REFERENCES analog.release(store, supplier, ordinal, revision, digest))""")
            sql.exec("CREATE INDEX analog_page ON analog.batch(actor, id)")
            sql.exec("""CREATE TABLE analog.proposal (
                batch_id bigint NOT NULL, actor uuid NOT NULL, workbook_row int NOT NULL CHECK (workbook_row BETWEEN 16 AND 18),
                result jsonb NOT NULL, PRIMARY KEY(batch_id, workbook_row),
                FOREIGN KEY(batch_id, actor) REFERENCES analog.batch(id, actor))""")
            for (role in Role.entries) {
                sql.exec("GRANT USAGE ON SCHEMA analog TO ${user(role)}")
                sql.exec("GRANT SELECT ON analog.identity TO ${user(role)}")
            }
            val manifest = "deployment, supplier, ordinal, store, authority, target, environment, schema, revision, semantics, origin, digest, artifact"
            sql.exec("GRANT SELECT($manifest) ON analog.release TO ${user(Role.RESOLVE)}, ${user(Role.DEPLOY)}")
            sql.exec("GRANT INSERT($manifest) ON analog.release TO ${user(Role.DEPLOY)}")
            sql.exec("GRANT INSERT(actor, attempt, fingerprint, store, supplier, ordinal, revision, digest) ON analog.batch TO ${user(Role.IMPORT)}")
            sql.exec("GRANT SELECT(id, actor, attempt, fingerprint, ordinal, revision, digest) ON analog.batch TO ${user(Role.IMPORT)}")
            sql.exec("GRANT INSERT(batch_id, actor, workbook_row, result) ON analog.proposal TO ${user(Role.IMPORT)}")
            sql.exec("GRANT SELECT(id, actor, supplier, ordinal, revision, digest, imported_at) ON analog.batch TO ${user(Role.LIST)}")
            sql.exec("GRANT SELECT(batch_id, actor, workbook_row, result) ON analog.proposal TO ${user(Role.LIST)}")
        }
    }

    fun owner(action: (Connection) -> Unit) = ownerConnect(database, action)
    fun roleConnection(role: Role, db: String = database) = connect(db, user(role), passwords.getValue(role), { 5_000 }, {})

    fun releaseWriter(beforeCommit: () -> Unit = {}, afterCommit: () -> Unit = {}) =
        ProdAnalogReleaseWriter(tx(Role.DEPLOY), beforeCommit, afterCommit)

    fun releaseReader(expected: ProdAnalogBinding = binding, asRole: Role = Role.RESOLVE) =
        ProdAnalogReleaseReader(tx(Role.RESOLVE, expected, asRole))

    fun imports(afterRow: (Int) -> Unit = {}, afterCommit: () -> Unit = {}, beforeReadReturn: () -> Unit = {}) =
        ProdAnalogImports(tx(Role.IMPORT), tx(Role.LIST), binding, afterRow, afterCommit, beforeReadReturn)

    private fun tx(role: Role, expected: ProdAnalogBinding = binding, asRole: Role = role) = ProdAnalogTransaction(
        database, user(role), expected, role == Role.DEPLOY || role == Role.IMPORT,
    ) { remaining, check -> connect(database, user(asRole), passwords.getValue(asRole), remaining, check) }
}

/** Verify connection and attested binding in the same transaction before every use. */
internal class ProdAnalogTransaction(private val database: String, private val role: String,
    val binding: ProdAnalogBinding, private val writable: Boolean,
    private val connect: (() -> Long, () -> Unit) -> Connection,
) {
    fun <T> bounded(run: TeachProposalJdbcStore.Tx.() -> T): T {
        val start = System.nanoTime()
        fun remaining() = (5_000 - (System.nanoTime() - start) / 1_000_000).also { if (it <= 0) throw ProdAnalogUnavailable() }
        return use(::remaining, { remaining() }, run)
    }

    fun <T> use(remaining: () -> Long, checkpoint: () -> Unit, run: TeachProposalJdbcStore.Tx.() -> T): T = try {
        checkpoint()
        connect(remaining, checkpoint).use { sql ->
            sql.isReadOnly = !writable
            sql.transactionIsolation = if (writable) Connection.TRANSACTION_READ_COMMITTED else Connection.TRANSACTION_REPEATABLE_READ
            sql.autoCommit = false
            val tx = TeachProposalJdbcStore.Tx(sql, remaining, checkpoint)
            val verified = tx.query("""SELECT current_database(), session_user, current_user, store, authority, target,
                environment, schema_version, current_setting('server_version_num')::int,
                current_setting('transaction_read_only') FROM analog.identity""") {
                it.next() && it.getString(1) == database && it.getString(2) == role && it.getString(3) == role &&
                    it.getObject(4, UUID::class.java) == binding.store && it.getObject(5, UUID::class.java) == binding.authority &&
                    it.getObject(6, UUID::class.java) == binding.destination && it.getString(7) == PROD_ANALOG && it.getInt(8) == 1 &&
                    it.getInt(9) in 160000..169999 && it.getString(10) == (if (writable) "off" else "on") && !it.next()
            }
            if (!verified) throw ProdAnalogUnavailable()
            tx.run().also { checkpoint() }
        }
    } catch (_: SQLException) { checkpoint(); throw ProdAnalogUnavailable() }
}
