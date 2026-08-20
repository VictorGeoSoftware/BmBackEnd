# Technical debt & known issues

Findings gathered while implementing and deploying observability Phase 0 + 1
(Aug 2026). Covers **BmBackEnd** and **BmInfra**.

Scope note: items already scheduled as future phases live in
`MONITORING_PLAN.md` and are cross-referenced rather than duplicated here. This
file is for things that are *unplanned, surprising, or a trap for the next
person* — including several that are only visible from the VPS.

Every item below was verified, not assumed; the evidence is recorded so it can
be re-checked when it is fixed.

---

## 🔴 High

### 1. BmInfra has no deployment automation

**Evidence:** `BmBackEnd/.github/workflows/deploy.yml` runs
`cd /opt/bm/BmInfra` and then `docker compose build` — with no `git fetch` or
`git pull` for that repo anywhere in the workflow. `DoclingBillReader`'s
workflow does the same.

**Impact:** application code is continuously deployed; infrastructure config is
not. The failure mode is not a broken deploy — it is a **green** deploy running
stale config. Merging observability to `main` rebuilt the backend correctly
while Compose still lacked `LOG_FORMAT=json`, and nothing reported a problem.
Git is not the source of truth for infrastructure; the only way to know what the
VPS runs is to SSH in and read the files.

**Fix:** mirror the BmBackEnd block — `git fetch` + `git reset --hard
origin/master` for `/opt/bm/BmInfra`, plus `docker compose up -d loki promtail
grafana`. Safe to automate: the tracked tree on the VPS was confirmed clean, and
`reset --hard` leaves untracked files (see §2) alone.

### 2. The VPS has an untracked `docker-compose.override.yml`

**Evidence:** `git status` in `/opt/bm/BmInfra` lists it as untracked. Compose
loads any file with that name **automatically** — no `-f` required.

```yaml
services:
  nginx:
    ports: ["80:80", "81:81", "443:443"]
  backend-prod:
    environment: [BM_AUTH_EMAIL_ALLOWLIST=${BM_AUTH_EMAIL_ALLOWLIST}]
  backend-qa:
    environment: [BM_AUTH_EMAIL_ALLOWLIST=${BM_AUTH_EMAIL_ALLOWLIST}]
```

**Impact:** the server's effective configuration is `docker-compose.yml` *plus*
this file, and it is invisible to anyone reading the repo — so local validation
does not necessarily reflect production. It is also load-bearing (it publishes
ports 80/443 and wires the auth allowlist) and exists in exactly one place: if
the VPS is rebuilt, it is gone, and public traffic silently stops being served.

**Verified harmless for observability** via `docker compose config`: `ports`
entries **append** (both `80` and `8090` are published) and `environment` merges
by key, so `LOG_FORMAT=json` survives.

**Fix:** it cannot simply be committed under that name — Compose would auto-load
it on developer machines and try to bind privileged ports. Either rename to
`docker-compose.vps.yml` and pass it explicitly with `-f` (requires updating
three deploy workflows), or keep it and treat the copy in
`BmInfra/docs/DEPLOY_OBSERVABILITY.md` as the backup of record.

### 3. `./gradlew build` fails on `qa`

**Evidence:** two failures in `GrantedUsersServiceTest`
(`deleteGrant wipes all user data…`, `deleteGrant skips token revocation…`) with
`IllegalStateException: Please call Database.connect()` at
`GrantedUsersService.kt:92`. Reproduced on `qa` at `fc2c2c9`, i.e. it predates
the observability work.

**Impact:** these two tests execute a real `deleteGrant` transaction while the
other 45 DB-backed tests skip cleanly without Docker. Because the build is red
regardless, **a green build cannot be used as a merge gate** — which removes the
main automated safety net before deploying to PROD.

**Fix:** give them the same Docker/Testcontainers guard the other DB tests use,
or provide the transaction manager they assume.

---

## 🟠 Medium

### 4. `/metrics` is unauthenticated and host-exposed

**Evidence:** `Application.kt:186` serves `prometheusMeterRegistry.scrape()`
with no token or auth check. `BmInfra/docker-compose.yml` publishes
`8081:8081` (prod) and `9081:8081` (qa) directly to the host.

**Impact:** if the VPS firewall permits those ports, endpoint names and traffic
volumes are readable from the internet, bypassing Nginx entirely. The `deny all`
added in Phase 0 protects only the paths that go *through* Nginx.

**Fix:** Phase 2 — bearer token or a separate internal port, and close
8081/9081 at the firewall. Tracked in `MONITORING_PLAN.md` §Phase 2.

### 5. `/health` conflates liveness and readiness

**Evidence:** `Application.kt:194` returns 503 when the DB is unreachable, and
Docker's healthcheck targets it.

**Impact:** a transient DB blip makes Docker restart an otherwise healthy
process, converting a brief dependency wobble into an outage.

**Fix:** split `/health/live` (no dependency checks, for Docker) from
`/health/ready` (current logic, for alerting). Tracked in Phase 2.

### 6. Log statements carry no business context

**Impact:** logs are structured and correlated by `requestId`, but individual
statements still lack user id, job id and bill id, so questions like "everything
that happened to this bill" are not answerable in Loki. Cheaper to fix *now*,
before saved queries and dashboards are built against the current field shape.

**Fix:** last open item of Phase 0.

### 7. Unbounded disk growth outside Docker logs

**Evidence:** `docling-temp` / `docling-uploads` volumes have no eviction
policy; `BmBackEnd/docker-compose.yml` still mounts `./logs:/app/logs`.

**Impact:** Docker log rotation (Phase 0) capped the largest risk, but uploaded
PDFs and intermediates still accumulate indefinitely.

**Fix:** Phase 3.

### 8. A missing env var breaks *all three* deploy pipelines

**Evidence:** reproduced —
`docker compose build backend-prod` fails with
`required variable GRAFANA_ADMIN_PASSWORD is missing a value`.

**Impact:** `${VAR:?}` plus the fact that Compose parses the whole file before
acting means one absent variable breaks BmBackEnd PROD, BmBackEnd QA **and**
DoclingBillReader deploys — none of which reference Grafana. Any future `:?`
variable adds the same trap, and the error names a service that has nothing to
do with the failing deploy.

**Fix:** prefer defaults (`${VAR:-sensible}`) over `:?` for anything not
security-critical; document required vars in `env.example` as they are added.

### 9. ~~Any push to `main` redeploys PROD, including docs-only changes~~ ✅ FIXED

**Was:** `.github/workflows/deploy.yml` triggered on `push` to `main`/`qa` with
no `paths:` or `paths-ignore:` filter, so editing a README rebuilt the image and
recreated `bm-backend-prod` — a production restart for a change that cannot
affect runtime, plus deploy history noisy enough to hide real deploys.

**Fixed:** added `paths-ignore` for `**.md`, `docs/**`, `.gitignore` and
`LICENSE`. `workflow_dispatch` is retained so a deploy can still be forced by
hand. Verified the workflow YAML parses and all keys resolve.

---

## 🟡 Low

### 10. Grafana's admin password is only honoured on first boot

`GF_SECURITY_ADMIN_PASSWORD` is read when Grafana initialises its database in
the `grafana-data` volume. Editing `.env` afterwards has no effect — changing it
needs `grafana-cli admin reset-admin-password` or wiping the volume (losing
dashboard edits).

### 11. The backend's "reuse caller-supplied request id" path is unreachable

`plugins/RequestId.kt` sanitises and reuses an inbound `X-Request-Id`, but Nginx
now sets `X-Request-Id $request_id` unconditionally on every proxied location
(a deliberate anti-forgery decision). Behind the proxy that branch never
executes, and `ObservabilityTest` covers behaviour that cannot occur in
production. Harmless as defence-in-depth, but do not read that test as evidence
the production path works.

### 12. `BM_ADMIN_TOKEN` is not set in PROD

PROD startup logs: `Admin API DISABLED (BM_ADMIN_TOKEN not set). Admin endpoints
will reject all requests.` Likely intentional, but it is a WARN on every boot in
production and should be confirmed as a decision rather than an oversight — if
intentional, silence it; if not, it is a silently disabled feature.

### 13. Branch names contain typos

`feature/implemeting-monitorization-phase0` (BmBackEnd) and
`feature/implementing-obesrvability-phase0` (BmInfra). Cosmetic, but they will
appear in history and PR links permanently.

### 14. Benign Micrometer warning on every boot

`A MeterFilter is being configured after a Meter has been registered…` — the
`env`/`service` common tags are registered after the JVM meters. Verified
harmless: a test scrapes the registry and asserts both labels are present on
emitted metrics. Noise, not a bug; worth silencing eventually so it does not
mask a real ordering problem later.

---

## Fixed during this work — do not regress

- **Promtail discarded every stack trace.** Its `output` stage replaces the log
  line with a single field, and `stack_trace` was never extracted, so exceptions
  reached Loki as a bare one-line message. Now re-attached; verified a 500
  arrives with its full ~4 kB trace.
- **ANSI colour codes inside JSON logs.** Ktor's default `CallLogging`
  formatter colourises output; under `LOG_FORMAT=json` the escapes were embedded
  in the JSON `message` field, breaking exact-match Loki queries. Replaced with
  an explicit plain formatter, with a regression test.
- **The `:81` QA vhost forwarded no `X-Request-Id`** on `/api/v1/` (the header
  had been placed on `/health` instead), making that vhost uncorrelatable.
- **`LOG_FORMAT` was a hardcoded literal**, so the documented "override in
  `.env`" rollback would have silently done nothing — precisely when it was most
  needed. Now `${PROD_LOG_FORMAT:-json}` / `${QA_LOG_FORMAT:-json}`.
