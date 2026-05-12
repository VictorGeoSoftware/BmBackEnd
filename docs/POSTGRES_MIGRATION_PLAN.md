# Postgres Migration Plan

Refactor the Backend persistence layer from SQLite (single-file, single-writer)
to PostgreSQL (multi-user, concurrent), keeping behavior unchanged, hardening
security, and respecting Clean Architecture + SOLID. TDD-first.

---

## 1. Current state (audit)

### Persistence stack
- **Engine:** SQLite via `org.xerial:sqlite-jdbc` + JetBrains Exposed 0.55.
- **Wiring:** `@/Users/victor/Documents/Personal/Projects/BM/Backend/src/main/kotlin/com/bm/backend/database/DatabaseFactory.kt:1-137`
  - `Database.connect(url, driver)` (no pool).
  - `SchemaUtils.createMissingTablesAndColumns(...)` for every table.
  - SQLite-specific PRAGMAs (`journal_mode=WAL`, `foreign_keys=ON`, `secure_delete=ON`, `synchronous=FULL`) via raw JDBC.
  - POSIX `600` permissions on the `.db`/`-wal`/`-shm` files.
  - `backfillUsageStartedAt` runs raw SQL on startup.
- **Config:** `@/Users/victor/Documents/Personal/Projects/BM/Backend/src/main/resources/application.yaml:9-13` hardcodes `jdbc:sqlite:price_tables.db` (env override `DB_URL`).
- **Tables:** `@/Users/victor/Documents/Personal/Projects/BM/Backend/src/main/kotlin/com/bm/backend/database/Entities.kt:1-95`
  - `price_table_results`, `termino_de_potencia`, `termino_de_energia`,
    `tarifas_potencia`, `tarifas_energia_base`, `tarifas_energia_unica`,
    `tax_settings`, `user_data`, `user_activity`.
- **Repositories:** all use `transaction { ... }` (no explicit isolation).
  - `PriceTableRepository` (~517 LoC, complex multi-table writes/reads).
  - `UserDataRepository` (PII AES-GCM via `EncryptionUtils`).
  - `UserActivityRepository` (online state, monthly counters).
  - `UserConsumptionRepository` (in-memory `MutableStateFlow`, **not DB-backed**).
- **Security:** `EncryptionUtils` (AES-256-GCM) + `DataMigration.encryptExistingUserData()`.
- **Container:** `@/Users/victor/Documents/Personal/Projects/BM/Backend/Dockerfile:1-52` mounts `/app/data` for the SQLite file.
- **Tests:** `PriceTableServiceTest` boots `DatabaseFactory.initTestDatabase()` against a fresh local `test_price_tables.db`.

### Business rules to preserve
1. **Price table upsert by natural key** (`fileName + companyName`): keep first existing row, delete duplicates, replace child rows atomically.
2. **Filtered queries** by `tarifaType` (case-insensitive); skip results lacking all three tarifa rows.
3. **Tax settings singleton** with defaults `IVA` / `IMPUESTO_ELECTRICO`.
4. **User data PII (`email`, `displayName`, `photoURL`)** stored AES-GCM encrypted.
5. **User activity:**
   - Idempotent online/offline transitions per email.
   - Monthly counter resets when `monthKey` changes.
   - `usageStartedAt` is the canonical immutable "first usage" timestamp; backfill if null using `COALESCE(last_connected_at, last_disconnected_at, updated_at)`.
6. **Cascade delete** of children when removing `price_table_results` rows.

### Gaps that Postgres must address
- Single-writer SQLite locks → contention with concurrent users.
- No connection pool → thread starvation under load.
- No read/write isolation tuning.
- No managed migrations (`createMissingTablesAndColumns` is a footgun under concurrency).
- No env-driven secret management for DB credentials.

---

## 2. Target architecture

### Stack
- **PostgreSQL 16** (managed or self-hosted; TLS required).
- **HikariCP** connection pool (sized per CPU + DB max_connections).
- **Flyway** for versioned schema migrations (replaces `createMissingTablesAndColumns` + ad-hoc backfills).
- **Exposed 0.55 jdbc** kept (Postgres dialect; minimal repository changes).
- **Testcontainers (Postgres)** for integration tests; in-memory H2 not used (dialect drift).
- **Optional pgcrypto** for column-level encryption later; for now keep app-side AES-GCM (no breaking change).

### Layering (Clean Architecture)
- **Domain (pure):** existing `models/` (DTOs/value objects). No JDBC types.
- **Application/use cases:** existing `services/`.
- **Ports (new):** introduce repository interfaces in `repositories/ports/`
  (`PriceTableRepositoryPort`, `UserDataRepositoryPort`, `UserActivityRepositoryPort`, `UserConsumptionRepositoryPort`).
  Services depend on the port, not the Exposed implementation (DIP).
- **Adapters (infra):** Exposed-backed implementations in `repositories/exposed/`.
- **Infrastructure:** `database/` package owns `DatabaseFactory`, `Hikari` config, `Flyway` runner. No business logic.
- **Composition root:** `Application.module()` wires concrete adapters to services.

This makes the persistence engine swappable, enables fast unit tests with fake adapters, and keeps services free of Exposed.

### Schema notes (Postgres-specific)
- `IntIdTable` → `SERIAL`/`BIGSERIAL` (Exposed handles it, but prefer `BIGSERIAL` for `price_table_results` and child tables for headroom).
- Add `UNIQUE (file_name, company_name)` on `price_table_results` to enforce the natural key DB-side and replace the manual lookup with `INSERT ... ON CONFLICT`.
- Add `ON DELETE CASCADE` to FKs for child tables (currently emulated in code).
- Add indexes:
  - `user_activity(month_key)` (used in monthly resets).
  - `user_data(uid)` (already unique).
  - `tarifas_*(termino_id)` for fast joins.
- Use `TIMESTAMPTZ` for time columns (currently `BIGINT` epoch). Migrate over multiple steps to avoid breaking API contracts; keep `BIGINT` initially, plan a follow-up.
- Use `TEXT` instead of `VARCHAR(n)` unless n is a real domain constraint.

### Concurrency & isolation
- Default `READ COMMITTED`.
- Wrap upsert/delete flows in **`SERIALIZABLE` or row-locking** transactions where natural-key collisions can race (price table upsert, user_activity transitions).
- Idempotency: prefer `INSERT ... ON CONFLICT DO UPDATE` (Postgres) over read-then-write.

### Security
- `DB_URL`, `DB_USER`, `DB_PASSWORD` from env (no defaults in prod). Fail fast if missing in non-dev profile.
- TLS: `sslmode=require` (or `verify-full` with CA bundle).
- Least-privileged DB role for the app (no `SUPERUSER`, no `CREATE DATABASE`); a separate role for migrations.
- Keep `EncryptionUtils` (AES-GCM) for PII at-application-layer (defense in depth, key rotation independent of DB).
- Audit logs already in repositories — keep.
- Disable Exposed `DEBUG` SQL logging in prod (`application.yaml` currently sets `org.jetbrains.exposed: DEBUG`).

### Deployment
- Add Postgres service to `docker-compose.yml` (dev) with named volume + healthcheck.
- Update `Dockerfile`: drop `/app/data` SQLite mount; backend becomes stateless.
- Document env vars in `DEPLOYMENT.md` and `QUICK_DEPLOY.md`.

---

## 3. Task breakdown (TDD-first, ordered)

Each task lists: **goal**, **tests first**, **impl**, **done-when**.
Keep PRs small; one task ≈ one PR.

### Phase A — Safety net & abstractions (no behavior change)

**A1. Lock current behavior with characterization tests**
- *Tests first:* expand `PriceTableServiceTest` and add `UserDataRepositoryTest`, `UserActivityRepositoryTest` covering: upsert-by-natural-key, duplicate cleanup, filtered queries, tax settings singleton, PII encryption round-trip, online/offline transitions, monthly counter reset, `usageStartedAt` backfill.
- *Impl:* none (or trivial test helpers).
- *Done when:* coverage of all repository public methods; tests pass on SQLite.

**A2. Introduce repository ports (interfaces)**
- *Tests first:* a fake in-memory adapter per port; service tests reuse the fake.
- *Impl:* extract interfaces (`...RepositoryPort`) in `repositories/ports/`. Existing classes implement them. Services depend on the interface. Composition root unchanged otherwise.
- *Done when:* services compile against ports only; no behavior change.

**A3. Replace `MutableStateFlow` consumption store with a proper port**
- *Tests first:* port contract tests (store/get/clear).
- *Impl:* leave in-memory adapter as default; document that this is intentionally non-persistent. Decide later if it must become DB-backed.
- *Done when:* port exists; no regressions.

### Phase B — Build infra for Postgres (still running on SQLite)

**B1. Add dependencies & profiles**
- *Tests first:* a smoke test that boots the app with `DB_VENDOR=sqlite` (current default) still passes.
- *Impl:* add `org.postgresql:postgresql`, `com.zaxxer:HikariCP`, `org.flywaydb:flyway-core`, `org.flywaydb:flyway-database-postgresql`, `org.testcontainers:postgresql` (test). Introduce `DB_VENDOR` env (`sqlite`|`postgres`).
- *Done when:* shadowJar builds; tests green.

**B2. Centralize DataSource creation behind a factory**
- *Tests first:* unit test that `DataSourceFactory` returns a Hikari pool for both vendors and validates required env vars.
- *Impl:* new `database/DataSourceFactory.kt` returning `javax.sql.DataSource`. `DatabaseFactory.init()` now calls `Database.connect(dataSource)`.
- *Done when:* SQLite path still works using a 1-connection Hikari pool.

**B3. Replace `createMissingTablesAndColumns` with Flyway**
- *Tests first:* Flyway migration test (apply on empty DB, schema matches Exposed metadata).
- *Impl:*
  - `V1__init.sql` describing current schema (works on both SQLite and Postgres? No — keep two folders: `db/migration/sqlite` and `db/migration/postgres`, select per vendor).
  - Remove `SchemaUtils.createMissingTablesAndColumns` from runtime; keep for tests only if needed.
  - Move `backfillUsageStartedAt` into `V2__backfill_usage_started_at.sql`.
- *Done when:* fresh boot creates schema via Flyway on both vendors; existing SQLite DB upgrades cleanly.

### Phase C — Postgres adapter & integration tests

**C1. Testcontainers integration test harness**
- *Tests first:* a base class `PostgresIntegrationTest` that spins up Postgres 16, runs Flyway, exposes a `DataSource`.
- *Impl:* test utility under `src/test/kotlin/.../testing/`.
- *Done when:* sample test passes on CI.

**C2. Run all repository tests against Postgres**
- *Tests first:* parameterize Phase-A tests across `[sqlite, postgres]`.
- *Impl:* fix any dialect issues that surface (e.g., `varchar` length, boolean defaults, `LIMIT` syntax — Exposed normally hides this).
- *Done when:* identical contract passes on both backends.

**C3. Replace manual upsert with `INSERT ... ON CONFLICT` for Postgres**
- *Tests first:* concurrent-upsert test (10 parallel calls with same natural key → exactly one row, no duplicates).
- *Impl:*
  - DB constraint `UNIQUE (file_name, company_name)` on `price_table_results` (Flyway migration).
  - Repository: vendor-aware upsert (Exposed's `upsert` since 0.50). Keep child-row replace logic but inside the same `SERIALIZABLE` retryable transaction.
- *Done when:* concurrency test green; existing single-thread tests still green.

**C4. Cascade deletes via FK**
- *Tests first:* delete parent → children gone (no manual loop).
- *Impl:* migration to add `ON DELETE CASCADE`. Simplify `deleteChildrenByResultId` to a single parent delete.
- *Done when:* repository code shrinks; behavior identical.

**C5. UserActivity transitions hardened**
- *Tests first:* concurrent setOnline/setOffline/increment for the same email; final state deterministic; counters never negative; `usageStartedAt` never overwritten.
- *Impl:* `INSERT ... ON CONFLICT (email) DO UPDATE` with conditional `usage_started_at = COALESCE(user_activity.usage_started_at, EXCLUDED.usage_started_at)`.
- *Done when:* concurrency test green.

### Phase D — Cutover

**D1. Data migration tool (SQLite → Postgres)**
- *Tests first:* tool reads a fixture SQLite file, writes to a Testcontainers Postgres, row counts and content (incl. encrypted PII bytes) match.
- *Impl:* a `gradle run`-able CLI under `tools/sqlite-to-postgres/` that copies tables in FK order, preserves IDs, runs inside a single transaction.
- *Done when:* dry run on a copy of `price_tables.db` succeeds and a verification query reports 0 diffs.

**D2. Configuration switch**
- *Tests first:* boot test asserting that with `DB_VENDOR=postgres` and missing creds the app refuses to start.
- *Impl:* update `application.yaml` to default to `postgres` for prod profile; keep `sqlite` only for local dev if desired (or drop entirely after cutover).
- *Done when:* prod boots only with valid Postgres creds.

**D3. Docker / deployment**
- *Tests first:* `docker compose up` healthcheck for backend + postgres turns healthy in CI smoke job.
- *Impl:*
  - Add `postgres:16-alpine` to `docker-compose.yml` with named volume, healthcheck, non-default port.
  - Drop `/app/data` from `Dockerfile` (backend is stateless).
  - Update `DEPLOYMENT.md`, `QUICK_DEPLOY.md`, `bm-backend.service`, `nginx-bm-backend.conf` if env loading changes.
- *Done when:* one-command bring-up works locally and on the deploy host.

**D4. Observability & ops**
- *Tests first:* unit test that `/health` reports DB connectivity (queries `SELECT 1`).
- *Impl:* extend `/health` to verify DB; add structured logging for slow queries; reduce `org.jetbrains.exposed` log level to `INFO` in prod.
- *Done when:* health endpoint reflects DB status; logs are quiet at INFO.

### Phase E — Cleanup

**E1. Remove SQLite code paths**
- *Tests first:* none new; existing Postgres-only suite must stay green.
- *Impl:* delete SQLite branch in `DataSourceFactory`, drop `org.xerial:sqlite-jdbc`, remove `applySecurityPragmas`, `restrictFilePermissions`, SQLite Flyway folder, and the legacy `price_tables.db`.
- *Done when:* repo has no SQLite references; `grep -r sqlite` is empty.

**E2. Documentation & runbooks**
- Update `README.md`, `AGENTS.md`, `DEPLOYMENT.md` with new env vars, backup/restore (`pg_dump`/`pg_restore`), rotation guidance for `BM_ENCRYPTION_KEY` and DB password.

**E3. Stretch (optional, separate epics)**
- Migrate epoch-millis columns to `TIMESTAMPTZ` (multi-step expand/contract).
- Move `UserConsumptionRepository` from in-memory to Postgres (only if multi-instance backend is planned).
- Add Prometheus/Micrometer metrics for pool + query timings.

---

## 4. Risks & mitigations
- **Dialect drift between dev (SQLite) and prod (Postgres):** mitigated by Phase C2 (parametrized tests) and Flyway-per-vendor; ultimately removed in Phase E.
- **Long migration window for production data:** Phase D1 tool is idempotent and re-runnable; cutover can be done with read-only SQLite snapshot.
- **Encryption key loss:** unchanged risk; document key escrow before cutover.
- **Connection storms:** Hikari `maximumPoolSize` tuned to ≤ `max_connections` of the Postgres instance; alarms on saturation.

---

## 5. Suggested execution order (single-track)
A1 → A2 → A3 → B1 → B2 → B3 → C1 → C2 → C3 → C4 → C5 → D1 → D2 → D3 → D4 → E1 → E2 → (E3 as backlog)

Each step ships green tests and is independently revertable.
