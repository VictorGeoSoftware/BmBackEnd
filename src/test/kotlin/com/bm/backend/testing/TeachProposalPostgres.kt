package com.bm.backend.testing

import com.bm.backend.models.TeachProposalImportIdentity
import java.sql.Connection
import java.util.UUID

internal class TeachProposalPostgres(
    private val catalog: TeachActivationPostgres,
    manifest: String,
    private val owner: (String, (Connection) -> Unit) -> Unit,
    private val promotionProviderId: UUID?,
    private val connect: (String, String, String, () -> Long, () -> Unit) -> Connection,
) {
    val identity = TeachProposalImportIdentity(UUID.randomUUID().toString().replace("-", ""), catalog.storeId, manifest, schemaVersion = 2)
    private val suffix = identity.storeId.take(12)
    val db = "qa_import_$suffix"
    private val writer = "import_writer_$suffix"
    private val reader = "import_reader_$suffix"
    private val validator = "validation_writer_$suffix"
    private val writerPassword = UUID.randomUUID().toString()
    private val readerPassword = UUID.randomUUID().toString()
    private val validatorPassword = UUID.randomUUID().toString()
    private val promoter = "promotion_writer_$suffix"
    private val promotionReader = "promotion_reader_$suffix"
    private val promoterPassword = UUID.randomUUID().toString()
    private val promotionReaderPassword = UUID.randomUUID().toString()

    fun provision(adminDb: String) {
        owner(adminDb) { sql ->
            sql.exec("CREATE ROLE $writer LOGIN PASSWORD '$writerPassword'")
            sql.exec("CREATE ROLE $reader LOGIN PASSWORD '$readerPassword'")
            sql.exec("CREATE ROLE $validator LOGIN PASSWORD '$validatorPassword'")
            sql.exec("CREATE DATABASE $db")
            sql.exec("REVOKE ALL ON DATABASE $db FROM PUBLIC")
            sql.exec("GRANT CONNECT ON DATABASE $db TO $writer, $reader, $validator")
            if (promotionProviderId != null) {
                sql.exec("CREATE ROLE $promoter LOGIN PASSWORD '$promoterPassword'")
                sql.exec("CREATE ROLE $promotionReader LOGIN PASSWORD '$promotionReaderPassword'")
                sql.exec("GRANT CONNECT ON DATABASE $db TO $promoter, $promotionReader")
            }
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
                imported_at timestamptz NOT NULL DEFAULT clock_timestamp(), UNIQUE (actor, attempt),
                validation_ref uuid NOT NULL DEFAULT gen_random_uuid() UNIQUE, UNIQUE (validation_ref, actor))""")
            sql.exec("CREATE INDEX import_actor_id ON proposals.batch (actor, id DESC)")
            sql.exec("""CREATE TABLE proposals.proposal (
                batch_id bigint REFERENCES proposals.batch(id), workbook_row integer NOT NULL CHECK (workbook_row BETWEEN 16 AND 18),
                provenance jsonb NOT NULL CHECK (provenance->'proposalCell'->>'a1' = 'I' || workbook_row::text),
                energy numeric(18,12) NOT NULL CHECK (energy >= 0), power1 numeric(18,12) NOT NULL CHECK (power1 >= 0),
                power2 numeric(18,12) NOT NULL CHECK (power2 >= 0), PRIMARY KEY(batch_id, workbook_row))""")
            sql.exec("""CREATE TABLE proposals.validation (
                validation_ref uuid PRIMARY KEY, actor text NOT NULL, validated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
                result text NOT NULL DEFAULT 'expected_prices_confirmed' CHECK (result = 'expected_prices_confirmed'),
                FOREIGN KEY (validation_ref, actor) REFERENCES proposals.batch(validation_ref, actor))""")
            sql.exec("REVOKE CREATE ON SCHEMA public FROM PUBLIC")
            sql.exec("GRANT USAGE ON SCHEMA proposals TO $writer, $reader, $validator")
            sql.exec("GRANT SELECT ON proposals.identity TO $writer, $reader, $validator")
            sql.exec("GRANT SELECT (id, actor, attempt, digest, revision, generation), INSERT (actor, attempt, digest, environment, supplier, revision, generation, manifest, source) ON proposals.batch TO $writer")
            sql.exec("GRANT INSERT ON proposals.proposal TO $writer")
            sql.exec("GRANT SELECT (id, actor, supplier, revision, generation, imported_at, validation_ref) ON proposals.batch TO $reader")
            sql.exec("GRANT SELECT ON proposals.proposal TO $reader")
            sql.exec("GRANT SELECT (id, actor, environment, revision, generation, manifest, validation_ref) ON proposals.batch TO $validator")
            sql.exec("GRANT INSERT (validation_ref, actor), SELECT (validation_ref, actor, validated_at) ON proposals.validation TO $validator")
            sql.exec("GRANT SELECT (validation_ref, actor, validated_at) ON proposals.validation TO $reader")
            if (promotionProviderId != null) provisionPromotion(sql, promotionProviderId)
        }
        catalog.sentinel { sql ->
            sql.exec("CREATE SCHEMA proposals")
            sql.exec("CREATE TABLE proposals.canary (fixture text PRIMARY KEY, proposal_count integer NOT NULL)")
            sql.exec("INSERT INTO proposals.canary VALUES ('prod-synthetic', 17)")
            sql.exec("CREATE TABLE proposals.validation (canary text PRIMARY KEY, validation_count integer NOT NULL)")
            sql.exec("INSERT INTO proposals.validation VALUES ('prod-synthetic', 23)")
        }
    }

    fun store(descriptor: TeachProposalImportIdentity = identity, database: String = db, writeRole: String = writer,
              afterRow: (Int) -> Unit = {}, beforeClaim: () -> Unit = {}, afterCommit: () -> Unit = {}) =
        TeachProposalJdbcStore(descriptor, database, writeRole, reader,
            { remaining, check -> connect(db, writer, writerPassword, remaining, check) },
            { remaining, check -> connect(db, reader, readerPassword, remaining, check) }, afterRow, beforeClaim, afterCommit)

    fun owner(run: (Connection) -> Unit) = owner(db, run)
    fun validationStore(descriptor: TeachProposalImportIdentity = identity, database: String = db, writeRole: String = validator,
                        beforeInsert: () -> Unit = {}, beforeCommit: () -> Unit = {}, afterCommit: () -> Unit = {}) =
        TeachProposalValidationJdbcStore(descriptor, database, writeRole,
            { remaining, check -> connect(db, validator, validatorPassword, remaining, check) }, beforeInsert, beforeCommit, afterCommit)

    fun validationConnection(database: String = db) = connect(database, validator, validatorPassword, { 5_000 }, {})
    fun connection(writable: Boolean, database: String = db) = if (writable)
        connect(database, writer, writerPassword, { 5_000 }, {}) else connect(database, reader, readerPassword, { 5_000 }, {})

    private fun provisionPromotion(sql: Connection, providerId: UUID) {
        sql.exec("ALTER TABLE proposals.validation ADD CONSTRAINT validation_promotion_target UNIQUE (validation_ref, actor)")
        sql.exec("""CREATE TABLE proposals.promotion (
            validation_ref uuid NOT NULL, actor text NOT NULL,
            destination text NOT NULL DEFAULT 'bm-pilot/skills-fixture' CONSTRAINT promotion_destination CHECK (destination = 'bm-pilot/skills-fixture'),
            op_key uuid NOT NULL DEFAULT gen_random_uuid() UNIQUE,
            digest text NOT NULL CONSTRAINT promotion_digest CHECK (digest ~ '^[0-9a-f]{64}$'),
            requested_at timestamptz NOT NULL DEFAULT clock_timestamp(),
            proposal_id bigint UNIQUE, opened_at timestamptz,
            CONSTRAINT promotion_completion CHECK ((proposal_id IS NULL AND opened_at IS NULL) OR
                (proposal_id IS NOT NULL AND proposal_id > 0 AND opened_at IS NOT NULL AND opened_at >= requested_at)),
            PRIMARY KEY (validation_ref, destination),
            CONSTRAINT promotion_event_fk FOREIGN KEY (validation_ref, actor) REFERENCES proposals.validation (validation_ref, actor))""")
        sql.exec("COMMENT ON TABLE proposals.promotion IS 'bm.teach.promotion/v1:$providerId'")
        sql.exec("GRANT USAGE ON SCHEMA proposals TO $promoter, $promotionReader")
        sql.exec("GRANT SELECT (id, catalog, manifest, environment, schema_version) ON proposals.identity TO $promoter, $promotionReader")
        sql.exec("GRANT SELECT (id, validation_ref, actor, supplier, revision, generation, environment, manifest) ON proposals.batch TO $promoter, $promotionReader")
        sql.exec("GRANT SELECT (validation_ref, actor, result, validated_at) ON proposals.validation TO $promoter, $promotionReader")
        sql.exec("GRANT SELECT (validation_ref, actor, destination, op_key, digest, proposal_id, opened_at) ON proposals.promotion TO $promoter, $promotionReader")
        sql.exec("GRANT INSERT (validation_ref, actor, digest), UPDATE (proposal_id, opened_at) ON proposals.promotion TO $promoter")
    }

    fun promotionStore(beforeReserveCommit: () -> Unit = {}, afterReserveCommit: () -> Unit = {},
        beforeFinalizeCommit: () -> Unit = {}, afterFinalizeCommit: () -> Unit = {},
        descriptor: TeachProposalImportIdentity = identity, afterRead: () -> Unit = {}, beforeOpenedReplay: () -> Unit = {}) = TeachProductionPromotionJdbcStore(
        descriptor, checkNotNull(promotionProviderId), db, promoter, promotionReader,
        { write, remaining, checkpoint -> connect(db, if (write) promoter else promotionReader,
            if (write) promoterPassword else promotionReaderPassword, remaining, checkpoint) },
        beforeReserveCommit, afterReserveCommit, beforeFinalizeCommit, afterFinalizeCommit, afterRead, beforeOpenedReplay,
    )

    fun promotionConnection(write: Boolean, database: String = db) = connect(database, if (write) promoter else promotionReader,
        if (write) promoterPassword else promotionReaderPassword, { 5_000 }, {})
}
