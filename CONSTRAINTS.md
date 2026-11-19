# Backend constraints

## Enséñame — QA activation harness

- Only synthetic fixture bytes, actors and disposable PostgreSQL 16. Do not mount
  activation from `Application.kt`, an environment variable or an operational migration.
  Never point this slice at a BM service or a hosted catalog, including for reads.
- Store identity and registry manifest must match before mounting and in every
  transaction. No fallback or automatic rule migration. The exact immutable registry
  binds supplier, workbook pin and V2 revision; update the extractor version for any
  semantic change. A different manifest cannot mount on the same fixture catalog.
- The QA writer cannot connect to the simulated PROD catalog. The sentinel owner
  seeds and compares its own snapshot; there is no cross-catalog datasource.
- Evidence lives only in the open view and expires after 300 seconds with no grace.
  Each test Netty composition produces a fresh in-memory HMAC key; restarting
  invalidates old receipts without changing a pointer. Never log/export proofs or keys.
- Persist only active pointer and successful change events plus read-only fixture
  identity. No rule bytes, prices, filenames, tokens, receipts or real identities.
  Catalog state survives only for the life of the disposable test container, then is
  destroyed. No archives, ordinary SQL migrations, or actual PROD verification.
- The four auth workers and four upload permits are shared with preceding modes.
  One 15-second deadline covers the request, without a new timeout on recheck or SQL.
  The JDBC fixture uses no external connection pool or service; work remains admitted
  until its connection closes. After sending commit, a timeout is an unknown result.
- Both the disposable PostgreSQL tests and the mandatory Pages/Netty/PostgreSQL test
  must actually execute. The opt-in joint is not evidence when omitted by `build`.
  The canonical hosted QA release checklist is independent and remains open.

## Enséñame — QA supplier selection harness

- The three filename aliases and four marker coordinates are synthetic only.
  Never infer supplier identity from a teaching pin, filenames beyond the explicit
  stems, free text, formulas, or references. Invalid evidence never falls back to
  the name. The selector does not apply the revision or establish authenticity.
- Selection is opt-in in `src/test` only, absent at application startup, with the
  usual QA access guard and no-store even when unmatched. Do not mount it with
  operational configuration, or connect it to normal Excel/PDF workflows.
- Use only the dedicated fixture QA read-only role and a single verified snapshot
  of identity and active pointer. No DML/DDL, `FOR UPDATE`, audit, payload retention,
  pointer cache, fallback to writer/sentinel/default rule, or cross-environment retry.
- Harness evidence is restricted to disposable PostgreSQL 16, controlled auth and
  loopback, with A/B committed revisions from real preview and confirmation. A
  protected simulated sentinel says nothing about deployed stores, credentials,
  hosted QA, actual PROD or model/agent isolation. The hosted checklist remains open.

## Enséñame — isolated QA Excel import harness

- Harness only: no runtime mount, operational migrations/configuration, pricing
  normalizer, PDF change, model, deployed service or hosted catalog. Do not
  read real files, consult another environment or fall back on any failure.
- The second synthetic workbook is not a teaching pin. Strict projection admits
  the audited `Tarifas` table with at most the optional `BM Identity` sheet. The
  actor sees only the three fixed rows of that batch, with exact decimal strings
  and citations. Blanks are not zero and absent periods are not materialized.
- Keep the selected revision, generation and manifest even if an activation
  races with the import. Re-authorize before the write or replay, not inside
  SQL; a later revocation cannot be made atomic with an in-flight commit.
- Four shared permits and workers, one 15-second deadline. Check deadline and
  observed peer closure before commit and wait for SQL close before releasing
  the slot. Once commit starts, a lost response is not evidence of rollback.
- Retain only successful receipts, cited rows and private dedupe keys for the
  disposable fixture lifetime. No workbook, filename, token, export or backup.
  The PDF test uses inert bytes and an isolated simulated repository with a fake
  downstream on loopback. Nothing here validates or promotes a revision.

## Enséñame — isolated QA result validation harness

- Confirm exactly one committed import of the current, nominated synthetic UID.
  A historical A remains eligible after B replaces it; B and other loads of A
  never inherit evidence. Neither validation nor replay reads or changes the
  catalog, proposals, extractor, or PDF path. This is not promotion eligibility.
- PostgreSQL assigns the private target during import and the timestamp during
  confirmation. Only an immutable successful event (reference, UID, timestamp,
  fixed outcome) is retained for the disposable fixture's lifetime. Do not retain
  labels, contact details, filenames, workbooks, prices, tokens or attempts here.
- Provisioning exists only in tests. Verify database, effective role, descriptor,
  QA environment, catalog binding, registry manifest and schema version. Writer
  and list roles have only column grants needed for their path. This is not RLS;
  every target lookup, join and replay is scoped to the verified UID. The runtime
  receives no provisioner, catalog or sentinel connection for validation.
- The four workers, four permits and 15-second deadline remain shared. Check the
  peer/budget before commit and close SQL resources before returning admission.
  Loss after commit starts is not proof of rollback. Recovery is scoped readback
  or intentional replay of the same target, without an extra event.

## Enséñame — simulated promotion harness

- Fixture-only PostgreSQL 16, Netty loopback and in-process fake. No Git, remote
  provider, network egress, access tokens at the fake, deployment, merge or
  publication. Do not mount from application configuration or use real data.
- One audited row per committed validation and fixed destination. Only final
  ID/time may change, with no reset or deletion before fixture teardown. Do not
  retain artifact bytes in SQL, input hashes, proposal values, workbook, chat,
  or refs/UID in logs. The fake only sees the fixed proposal metadata and data
  diff, with neither validation reference nor actor/credentials.
- A timeout or response loss is not rollback. Recovery needs current
  authorization, the original committed key/digest, and the same fake state and
  identity. No blind resend on failed lookup, identity loss or receipt mismatch;
  a committed fake effect can remain pending in SQL. The conditional guarantee
  applies only within this disposable fixture, not to hosted providers.
- Promotion rechecks use the original deadline. If the caller times out or
  disconnects during a blocking verifier call, its decision is discarded but
  the shared upload permit is held until the verifier actually returns. A
  completion callback releases the permit without resuming the operation.
  Other Enséñame routes retain their existing admission behavior.
- No changes to the default validation v2 fixture or runtime; the opt-in adds
  only the promotion audit relation and the redundant FK-target uniqueness.
  Hosted QA, actual protected branch permissions and release remain unverified.
