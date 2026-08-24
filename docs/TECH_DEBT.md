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

### 18. ~~🔴 `PriceTableRoutes` exposes 8 endpoints with no authentication~~ ✅ FIXED

**Was:** `PriceTableRoutes.kt` contained no auth call of any kind — the only
route file without one — and no global `Authentication` plugin covered it. All
eight endpoints were reachable through Nginx on `/api/v1/`, including
`DELETE /clear-all-data`. Confirmed against QA rather than inferred: an
unauthenticated POST returned **500, not 401**, meaning the body was accepted,
parsed and logged before failing on content.

**Fixed** across four repositories, gated by caller:

| Endpoint | Gate |
|---|---|
| `clear-all-data`, `delete price-table-results`, `upload-price-proposal`, `fetch-total-prices` (x2), `price-table-tax-settings` (x2) | admin (`requireAdminFirebaseUser`) |
| `GET /price-table-results` | authenticated — BmApp users are regular granted accounts, not admins |
| `POST /batch-process-price-tables` | unchanged in code; `deny all` at the proxy |

`batch-process-price-tables` deliberately keeps no Firebase auth: the n8n
workflow posts to `http://backend-prod:8081/...` over the internal Docker
network and holds no token. Adding auth there would have broken price fetching —
caught during implementation. Blocking it at the proxy closes external access
because that internal path never traverses Nginx.

BmWeb forwards the caller's token (`authFetch` client-side,
`authorizationHeader` in route handlers, both centralised so a new call site
cannot silently omit it). BmApp now sends a token on `getPriceTableResults()`,
the one call in `PriceTableApi` that did not.

`ApplicationTest` asserted the old anonymous-read contract and failed on the
change — the regression net working — and now asserts 401.

**Residual risk:** ports 8081/9081 remain published to the host, so direct
access bypasses the proxy. Every endpoint now requires a token regardless, so
only `batch-process-price-tables` is reachable that way. Closing those ports is
**#4**, which needs sequencing because both CI health checks use 8081.

**Known consequence:** BmApp installs older than the token release fail on the
price table screen until updated. Accepted deliberately — a feature flag was
built to stage this and then removed, on the grounds that a flag which never
gets flipped is permanent debt in the auth path.

### 19. 🔴 The rate limiter is one shared 100 req/min bucket for the entire backend — ⏸️ DEFERRED, accepted risk

**Evidence:** `Application.kt` installed
`global { rateLimiter(limit = 100, refillPeriod = 60.seconds) }`. Ktor 2.3.12's
`RateLimitProviderConfig.kt:105` documents the default key:

> *"By default, the key is a `Unit`, so all requests share the same
> Rate-Limit."*

No `requestKey` was configured, so the limit is **not** per client. All callers
draw from a single 100-requests-per-minute bucket.

**Confirmed to be the only rate limiting in the stack:** there is no
`limit_req` / `limit_conn` in `BmInfra/nginx/nginx.conf`,
`nginx-bm-backend.conf` or `deploy-remote.sh`, and no Flask limiter in
DoclingBillReader. Nothing backstops it.

**Impact:** two dimensions, both bad.
- *Availability:* a handful of concurrent users can exhaust the budget for
  everyone. Each BmApp screen that issues several calls consumes a
  disproportionate share.
- *Security:* as abuse protection it is close to useless — a single attacker
  trivially denies service to all legitimate users, which is a cheaper attack
  than the one the limit was meant to prevent.

**Partially mitigated:** the limiter was moved off `global` onto a named
provider scoped to `/api/v1`, so `/`, `/health*` and `/metrics` no longer spend
the budget — previously a Docker healthcheck or a Prometheus scrape competed
with real traffic, and a 429 on a healthcheck would have restarted a healthy
container. Limit and window are unchanged. Two tests pin this: probes are not
limited, and `/api/v1` still is.

**Fix (needs a decision, not just code):** set `requestKey` to the caller
identity and raise the limit to something defensible per client.

**⏸️ DEFERRED (Aug 2026) — accepted risk, with a tripwire.** The short-term user
base is ~20, and the proper fix is a policy decision rather than a code change.
The reasoning and the symptom are recorded in a comment directly above the
`register(API_RATE_LIMIT)` block in `Application.kt`, so whoever hits this does
not have to rediscover it.

**Know the arithmetic before assuming ~20 users is safe.** The bucket is shared,
so each concurrent user effectively gets `100 / N` requests per minute:

| Concurrent users | Requests/min each |
|---|---|
| 5 | 20 |
| 10 | 10 |
| **20** | **5** |

At 20 active users that is **5 requests/min each**. A single BmApp screen that
calls 4–5 endpoints on open consumes a user's whole minute, and 20 users opening
the app simultaneously spend the entire budget at once. This may already be
happening rather than being a future risk.

**Tripwire — check before assuming it is fine:**

```
{service="bm-backend", env="prod"} |= "429"
```

Empty ⇒ the deferral is safe. Non-empty ⇒ users are already being throttled and
are experiencing it as "the app is broken".

**Cheap interim mitigation if that query is not empty:** raise `limit = 100` to
~2000. One line, no design decision needed. It keeps the shared-bucket shape —
still wrong in principle, still weak as abuse protection — but removes the
availability risk while the real fix waits.

**When fixing properly, prefer the Firebase UID over the client IP.** An IP key
is the obvious choice and the wrong one here, because BmApp is mobile:

- **Many users, one IP.** Mobile carriers use carrier-grade NAT (CGNAT), so
  thousands of subscribers share a small pool of public IPv4 addresses. Two
  users on the same carrier can appear as the same IP — which recreates exactly
  the shared-bucket problem this item is about, just smaller. Office Wi-Fi has
  the same effect.
- **One user, many IPs.** Switching Wi-Fi ↔ cellular changes the IP instantly;
  so do tower handoffs and DHCP renewals. A user's bucket silently resets as
  they move, defeating the limit.
- IPv6 usually gives a device its own address, but carriers rotate prefixes, so
  it is not a guarantee either.

The Firebase UID is stable, gives exactly one bucket per user, and is immune to
both CGNAT and network switching. Unauthenticated endpoints are a small set and
can fall back to an IP key.

If an IP key is used anywhere, note that the backend sits behind Nginx, so
`remoteHost` is the proxy for every request. It must read the forwarded client
IP — and trust `X-Forwarded-For` **only** from Nginx, or a caller can spoof the
header and mint an unlimited bucket per request.

## 🟠 Medium

### 4. `/metrics` is unauthenticated and host-exposed — 🟡 PARTIALLY FIXED

**Was:** `Application.kt:186` served `prometheusMeterRegistry.scrape()` with no
token or auth check, while `BmInfra/docker-compose.yml` publishes `8081:8081`
(prod) and `9081:8081` (qa) directly to the host. The `deny all` added in
Phase 0 protects only the paths that go *through* Nginx, so the host ports
bypassed it entirely.

**Fixed (auth):** `MetricsAuthService` now requires
`Authorization: Bearer $METRICS_TOKEN`, compared in constant time. It **fails
closed** — with `METRICS_TOKEN` unset the endpoint returns 401 to everyone,
which is the correct setting until Prometheus is actually deployed. The var is
wired into both backend services in `BmInfra/docker-compose.yml` and documented
in `env.example`. Covered by `MetricsAuthServiceTest` plus a route test
asserting an unauthenticated `GET /metrics` is rejected.

**Still open (firewall):** ports 8081/9081 remain published to the host. The
endpoint no longer leaks without a token, so this is now defence-in-depth
rather than an open door.

⚠️ **Coupling to watch:** both deploy workflows health-check
`http://217.154.181.175:8081/health` over the public interface. Closing 8081 at
the firewall will break CI unless the check is moved to an SSH-tunnelled
`curl` or to `docker compose exec` first. Sequence that change carefully.

### 5. ~~`/health` conflates liveness and readiness~~ ✅ FIXED

**Was:** a single `/health` returned 503 when the DB was unreachable, and
Docker's healthcheck targeted it — so a transient DB blip made Docker restart
an otherwise healthy process, converting a brief dependency wobble into an
outage that a restart cannot fix.

**Fixed:** `/health/live` (no dependency checks) and `/health/ready` (DB check)
now exist, with `/health` retained as a byte-compatible alias of `/health/ready`
so deploy workflows, Nginx and scripts keep working unchanged. All four Docker
healthchecks (`BmBackEnd/Dockerfile`, `BmBackEnd/docker-compose.yml`,
`BmInfra/docker-compose.yml` ×2) now target `/health/live`.

Implemented behind `DatabaseHealthPort` / `ExposedDatabaseHealthCheck` +
`HealthService`, following the `TransactionRunnerPort` pattern from item 3 —
the health route was the last place in the codebase where the transport layer
imported Exposed directly, which `AGENTS.md` forbids.

Also fixed in passing: the old DB check swallowed the exception with
`catch (_: Exception)`, so an outage produced a bare 503 with nothing in the
logs to explain it. It now logs at WARN with the stack trace.

**Tests:** `HealthServiceTest` (including one asserting liveness never calls the
database, via a call counter) and four route tests, one of which pins `/health`
to `/health/ready` so the alias cannot silently drift and break CI.

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

### 7. ~~Unbounded disk growth outside Docker logs~~ ✅ CLOSED — was not a real issue

**This entry was wrong.** It was written from reading `docker-compose.yml`
rather than the code, and every claim in it was measured false (Aug 2026):

| Claim | Measured reality |
|---|---|
| `docling-temp` / `docling-uploads` accumulate uploads | **0 B, both.** Nothing has ever been written to them |
| Uploaded PDFs and intermediates persist | `/tmp` in both Docling containers: **8 K**, no leaked files |
| `BmBackEnd/docker-compose.yml` mounts `./logs:/app/logs` on the VPS | That file is local-dev only; the mount is **not** in `BmInfra/docker-compose.yml` |

Root cause of the mistake: all three Flask servers set
`UPLOAD_FOLDER = tempfile.gettempdir()`
(`docling_customer_data_extraction_api_server.py:48`,
`docling_price_tables_extraction_api_server.py:41`), so uploads never touch the
mounted volumes at all. And cleanup *does* exist on every request path — the
price-tables server is `finally`-guarded; the customer-data server duplicates
the delete across the success and `except` branches.

**Disk at time of writing: 111 G used of 697 G (16%).** A `docker builder prune`
reclaimed 6 GB. Note that `docker system df` reported "Build cache usage:
62.9GB", which is misleading — most of those records are `SHARED` with live
images and are not reclaimable; the prune's own `Total: 5.6GB` was the honest
figure.

**Residual, low priority:** the customer-data server's delete is not in a
`finally`, so an abnormal exit (gunicorn `--timeout 300` worker kill, OOM)
could leak a file, and there is no sweeper as a backstop. Not currently
leaking. Its failure path also uses `print()` rather than the logger, so a
repeated delete failure would be invisible in Loki.

**Lesson:** measure before building. Implementing Phase 3 as originally
specified would have produced a cleanup cron job for two permanently empty
directories.

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
