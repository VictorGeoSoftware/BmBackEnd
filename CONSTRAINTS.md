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
