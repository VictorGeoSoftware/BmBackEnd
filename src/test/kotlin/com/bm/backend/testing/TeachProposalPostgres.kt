package com.bm.backend.testing

import com.bm.backend.models.TeachProposalImportIdentity
import java.sql.Connection
import java.util.UUID

internal class TeachProposalPostgres(
    private val catalog: TeachActivationPostgres,
    manifest: String,
    private val owner: (String, (Connection) -> Unit) -> Unit,
    private val connect: (String, String, String, () -> Long, () -> Unit) -> Connection,
) {
    val identity = TeachProposalImportIdentity(UUID.randomUUID().toString().replace("-", ""), catalog.storeId, manifest)
    private val suffix = identity.storeId.take(12)
    val db = "qa_import_$suffix"
    private val writer = "import_writer_$suffix"
    private val reader = "import_reader_$suffix"
    private val writerPassword = UUID.randomUUID().toString()
    private val readerPassword = UUID.randomUUID().toString()

    fun provision(adminDb: String) {
        owner(adminDb) { sql ->
            sql.exec("CREATE ROLE $writer LOGIN PASSWORD '$writerPassword'")
            sql.exec("CREATE ROLE $reader LOGIN PASSWORD '$readerPassword'")
            sql.exec("CREATE DATABASE $db")
            sql.exec("REVOKE ALL ON DATABASE $db FROM PUBLIC")
            sql.exec("GRANT CONNECT ON DATABASE $db TO $writer, $reader")
        }
        owner(db) { sql ->
            sql.exec("CREATE SCHEMA proposals")
            sql.exec("""CREATE TABLE proposals.identity (
                only_identity boolean PRIMARY KEY CHECK (only_identity), id text NOT NULL, catalog text NOT NULL,
                manifest text NOT NULL, environment text NOT NULL CHECK (environment = 'qa'), schema_version integer NOT NULL)""")
            sql.prepareStatement("INSERT INTO proposals.identity VALUES (true, ?, ?, ?, ?, ?)").use {
                it.setString(1, identity.storeId); it.setString(2, identity.catalogId); it.setString(3, identity.manifest)
                it.setString(4, identity.environment); it.setInt(5, identity.schemaVersion); it.executeUpdate()
            }
            sql.exec("""CREATE TABLE proposals.batch (
                id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY CHECK (id > 0), actor text NOT NULL,
                attempt text NOT NULL CHECK (attempt ~ '^[0-9a-f]{32}$'), digest text NOT NULL CHECK (digest ~ '^[0-9a-f]{64}$'),
                environment text NOT NULL CHECK (environment = 'qa'), supplier text NOT NULL CHECK (supplier = 'gesternova'),
                revision text NOT NULL CHECK (revision ~ '^[0-9a-f]{64}$'), generation bigint NOT NULL CHECK (generation > 0),
                manifest text NOT NULL CHECK (manifest ~ '^[0-9a-f]{64}$'), source text NOT NULL CHECK (source IN ('filename', 'content')),
                imported_at timestamptz NOT NULL DEFAULT clock_timestamp(), UNIQUE (actor, attempt))""")
            sql.exec("CREATE INDEX import_actor_id ON proposals.batch (actor, id DESC)")
            sql.exec("""CREATE TABLE proposals.proposal (
                batch_id bigint REFERENCES proposals.batch(id), workbook_row integer NOT NULL CHECK (workbook_row BETWEEN 16 AND 18),
                provenance jsonb NOT NULL CHECK (provenance->'proposalCell'->>'a1' = 'I' || workbook_row::text),
                energy numeric(18,12) NOT NULL CHECK (energy >= 0), power1 numeric(18,12) NOT NULL CHECK (power1 >= 0),
                power2 numeric(18,12) NOT NULL CHECK (power2 >= 0), PRIMARY KEY(batch_id, workbook_row))""")
            sql.exec("GRANT USAGE ON SCHEMA proposals TO $writer, $reader")
            sql.exec("GRANT SELECT ON proposals.identity TO $writer, $reader")
            sql.exec("GRANT SELECT (id, actor, attempt, digest, revision, generation), INSERT ON proposals.batch TO $writer")
            sql.exec("GRANT INSERT ON proposals.proposal TO $writer")
            sql.exec("GRANT SELECT (id, actor, supplier, revision, generation, imported_at) ON proposals.batch TO $reader")
            sql.exec("GRANT SELECT ON proposals.proposal TO $reader")
        }
        catalog.sentinel { sql ->
            sql.exec("CREATE SCHEMA proposals")
            sql.exec("CREATE TABLE proposals.canary (fixture text PRIMARY KEY, proposal_count integer NOT NULL)")
            sql.exec("INSERT INTO proposals.canary VALUES ('prod-synthetic', 17)")
        }
    }

    fun store(descriptor: TeachProposalImportIdentity = identity, database: String = db, writeRole: String = writer,
              afterRow: (Int) -> Unit = {}, beforeClaim: () -> Unit = {}, afterCommit: () -> Unit = {}) =
        TeachProposalJdbcStore(descriptor, database, writeRole, reader,
            { remaining, check -> connect(db, writer, writerPassword, remaining, check) },
            { remaining, check -> connect(db, reader, readerPassword, remaining, check) }, afterRow, beforeClaim, afterCommit)

    fun owner(run: (Connection) -> Unit) = owner(db, run)
    fun connection(writable: Boolean, database: String = db) = if (writable)
        connect(database, writer, writerPassword, { 5_000 }, {}) else connect(database, reader, readerPassword, { 5_000 }, {})
}
