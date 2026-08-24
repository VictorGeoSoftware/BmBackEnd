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

### 1. ~~BmInfra has no deployment automation~~ ✅ FIXED

**Was:** `BmBackEnd/.github/workflows/deploy.yml` ran `cd /opt/bm/BmInfra` then
`docker compose build`, with no `git fetch`/`git pull` for that repo anywhere.
`DoclingBillReader`'s workflow did the same. Application code was continuously
deployed; infrastructure config was not. The failure mode was not a broken
deploy but a **green** one running stale config.

**Fixed:** `BmInfra/.github/workflows/deploy.yml` now triggers on push to
`master` and syncs the VPS itself. Putting the pull inside BmBackEnd's workflow
would *not* have fixed it — infra changes would still only land incidentally,
whenever backend code happened to be pushed next.

Safety properties: refuses to run if tracked files were hand-edited on the VPS;
validates with `docker compose config` before applying; `--no-build` so
application images stay owned by their own repos; `up -d` is idempotent.
Bind-mounted configs (Loki, Promtail, Grafana dashboards, `nginx.conf`) are
reloaded explicitly, since changing a file's *contents* does not change the
container spec and `up -d` alone would leave them running stale.

**It proved its worth on the first run.** `docling-price-tables`, `n8n-prod` and
`n8n-qa` were recreated: nothing depends on them, so the manual
`up -d backend-prod backend-qa nginx` during deployment had never included them,
and they had been running for four days **without log rotation** — the largest
disk risk in `MONITORING_PLAN.md` §4. Nothing had reported it.


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

### 3. ~~`./gradlew build` fails on `qa`~~ ✅ FIXED

**Was:** two `GrantedUsersServiceTest` cases failed with
`IllegalStateException: Please call Database.connect()` at
`GrantedUsersService.kt:92`, so a green build could not be used as a merge gate.

**Root cause was architectural, not a test defect.** `GrantedUsersService`
called Exposed's `transaction { }` directly, reaching past the repository layer
into persistence — contrary to the Clean Architecture boundary in `AGENTS.md`.
The test is a genuine unit test with in-memory repositories and correctly needs
no Docker; it failed because the service bypassed those repositories entirely.

**Fixed:** introduced `TransactionRunnerPort` alongside the existing repository
ports, with `ExposedTransactionRunner` in the infrastructure layer and
`DirectTransactionRunner` for tests. `GrantedUsersService` no longer imports
Exposed — it was the only service that did. Added an assertion that the
four-table wipe runs as exactly one unit of work, so the atomicity guarantee is
now covered rather than merely implied.

**Verified:** `./gradlew build` green — 112 tests, 0 failures, 0 skipped.

### 18. 🔴 `PriceTableRoutes` exposes 8 endpoints with no authentication

**Evidence:** `PriceTableRoutes.kt` contains no `requireAuthenticatedFirebaseUser`,
`requireAdminFirebaseUser` or `adminAuthService` call, and `Application.kt`
installs no `Authentication` plugin and wraps nothing in `authenticate { }`.
Every other route file gates its endpoints (7 files, all covered).

Unauthenticated endpoints, all reachable through Nginx on `/api/v1/`:

| Endpoint | Effect |
|---|---|
| `DELETE /clear-all-data` | deletes all price table data |
| `DELETE /price-table-results` | deletes selected proposals |
| `POST /batch-process-price-tables` | writes price data |
| `POST /upload-price-proposal` | uploads + stores an extracted PDF |
| `POST /fetch-total-prices` (x2) | triggers the n8n workflow |
| `GET /price-table-results` | reads price data |
| `GET /price-table-tax-settings` | reads tax config |

**Impact:** as written, anyone who can reach the host can wipe the price tables.
Not verified against the running server — confirming it means destroying data.
Check safely with a read: `curl -o /dev/null -w '%{http_code}'
http://<host>:8091/api/v1/price-table-results` (200 = unauthenticated).

**Fix requires a decision, not just a patch.** Some of these are plausibly
machine-to-machine (`/batch-process-price-tables` accepts raw Docling
extraction JSON, so n8n likely calls it), and n8n holds no Firebase token.
Split them:
- user-facing -> `requireAuthenticatedFirebaseUser`, as everywhere else
- machine-facing -> shared secret, or restrict to the Docker network in Nginx
- `/clear-all-data` -> admin-only, if it belongs in production at all

**Confirmed against the running QA server**, not just read from code: an
unauthenticated `POST /api/v1/batch-process-price-tables` returned **500**, not
401 — the body was accepted, parsed and logged before failing on content. If
anything gated the route the request would have been rejected before the body
was read.

**Caller mapping (already done, to inform the fix):**

| Endpoint | Called by |
|---|---|
| `batch-process-price-tables` | neither BmWeb nor BmApp -> machine caller (n8n) |
| `clear-all-data` | BmWeb (`api/price-proposals/clear-all-data`, sidebar) |
| `upload-price-proposal` | BmWeb (`api/price-proposal/upload`) |
| `price-table-results` | BmWeb (`api/price-proposals`) |
| `fetch-total-prices` | BmWeb (`api/price-proposals/fetch-total-prices`) |

So all but one are user-facing through BmWeb's Next.js server routes and can
take Firebase auth; only `batch-process-price-tables` needs a machine-to-machine
mechanism. Open question for the fix: whether BmWeb's server routes already
forward the caller's Firebase ID token.

Found incidentally while adding log context — it is unrelated to observability
and outranks everything remaining in `MONITORING_PLAN.md`.

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

⚠️ **Coupling to watch:** both deploy workflows health-check
`http://217.154.181.175:8081/health` over the public interface. Closing 8081 at
the firewall will break CI unless the check is moved to an SSH-tunnelled
`curl` or to `docker compose exec` first. Sequence that change carefully.

### 5. `/health` conflates liveness and readiness

**Evidence:** `Application.kt:194` returns 503 when the DB is unreachable, and
Docker's healthcheck targets it.

**Impact:** a transient DB blip makes Docker restart an otherwise healthy
process, converting a brief dependency wobble into an outage.

**Fix:** split `/health/live` (no dependency checks, for Docker) from
`/health/ready` (current logic, for alerting). Tracked in Phase 2.

### 6. ~~Log statements carry no business context~~ ✅ FIXED

**Was:** logs were correlated by `requestId`, but individual statements carried
no business identifiers, so "everything that happened to this job or user" was
unanswerable. Route logging used string interpolation (`"Job $jobId completed"`),
which is unqueryable and makes every occurrence a unique message string.

**Fixed:** `StructuredArguments.kv()` for `userId`, `userEmail`, `jobId`,
`fileName`, `payloadBytes` — JSON fields under `LOG_FORMAT=json`, `key=value` in
text mode. Promtail extracts them as structured metadata. Messages are now
constant strings, so occurrences can be grouped and counted.

Also fixed two things found in the same pass: background consumption jobs lost
`requestId` entirely (detached coroutine, no MDC), and `PriceTableRoutes` logged
full request bodies and extraction payloads at INFO — unbounded Loki storage
driven by request size, with document content going into log storage.

**Verified in QA:** `"payloadBytes":13` with `requestId` returned as structured
metadata from a Loki query.

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

### 12. ~~`BM_ADMIN_TOKEN` is not set in PROD~~ ✅ RESOLVED (as intended)

PROD startup logged `Admin API DISABLED (BM_ADMIN_TOKEN not set)` as a **WARN**
on every boot, leaving it ambiguous whether the admin API was deliberately off
or misconfigured — and putting a permanent entry in Grafana's warnings panel.

**Scope is narrower than the log message suggests.** There are two independent
admin mechanisms, and this one gates a single endpoint:

| Mechanism | Gates | PROD |
|---|---|---|
| `requireAdminFirebaseUser` | granted-users CRUD, collected prices, access checks | working |
| `AdminAuthService` (`BM_ADMIN_TOKEN`) | `POST /admin/reset-device-binding` only | disabled |

So day-to-day user management through BmWeb is unaffected — it authenticates
Firebase admin users, not this shared secret. The only capability unavailable in
PROD is resetting a user's device binding, which would otherwise have to be done
with a manual `user_data` update.

**Decision:** leave unset. A shared-secret API is not worth the surface for one
rarely-used operation. Fail-closed remains the right default.

**Fixed:** downgraded to INFO with the rationale in `AdminAuthService`'s KDoc.
Behaviour unchanged.

⚠️ Revisit if device-binding resets become frequent: the current alternative is
hand-editing the database, which is riskier than the API it replaces.

### 13. ~~An orphan `docling-api` container has run for 6 months~~ ✅ FIXED

**Evidence:** `docker ps -a` shows `docling-api  Up 6 months  docling-api`, with
empty `com.docker.compose.service` and `com.docker.compose.project` labels —
i.e. it predates the Docker migration and is not managed by Compose. The
Compose-managed one is `bminfra-docling-api-1`.

**Impact:** it consumes RAM indefinitely, is invisible to `docker compose`
commands, and was the direct cause of the Promtail/Loki retry storm (it had no
Compose labels, so its log stream had no labels and Loki rejected every push).
It cannot be serving traffic, since Compose's docling-api holds port 5000.

**Fixed:** removed with `docker rm -f docling-api` (2026-08-24). Covered in spirit by
`BmInfra/TODO.md:24` ("remove old deployment leftovers"). The Promtail config
was hardened independently so that removing it is hygiene, not a dependency.

### 14. Docling runs Flask's development server in production

**Evidence:** `Dockerfile` runs `CMD ["python", "docling_..._api_server.py"]`
and the script ends in `app.run(host='0.0.0.0', port=port, debug=False)`.
Meanwhile the module docstring in the same file describes running under
``gunicorn --preload`` — so the code and the deployment disagree.

**Impact:** Werkzeug's built-in server is explicitly not intended for
production use. Docling is on the critical path for every bill upload, and PDF
extraction is CPU-heavy and long-running, which is exactly the workload the dev
server handles worst.

**Fix:** run under gunicorn as the docstring already assumes, sizing workers
against the ML memory footprint.

### 17. ~~`qa` is behind `main` — QA is not a faithful rehearsal of PROD~~ ✅ FIXED

**Evidence:** `git diff origin/qa origin/main -- src/` shows
`AdminAuthService.kt` differing by 14 lines: the admin-logging change was
committed to `main` directly and never flowed back to `qa`.

**Impact:** `MONITORING_PLAN.md` §5 requires the code on both branches to be
identical, with only environment variables differing — otherwise QA stops being
a rehearsal of PROD and changes reach production untested. Divergence also
accumulates: the next `qa` → `main` merge has to reconcile it.

**Fixed:** merged `main` into `qa` and pushed (2026-08-24); `git diff origin/qa
origin/main -- src/` is now empty. Then treat "commit to `main` directly" as the exception it
should be — the workflow in §5 is develop on `qa`, validate, promote.

### 15. Branch names contain typos

`feature/implemeting-monitorization-phase0` (BmBackEnd) and
`feature/implementing-obesrvability-phase0` (BmInfra). Cosmetic, but they will
appear in history and PR links permanently.

### 16. Benign Micrometer warning on every boot

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
