# BM Monitoring & Observability Plan

> **Scope:** `BmBackend` (Ktor/Kotlin), plus the shared stack defined in
> `BmInfra/docker-compose.yml` (Docling ×2, Postgres, n8n ×2, Nginx).
>
> **Primary goal:** have a proper framework to **inspect, search and correlate
> logs and system state** across all BM services — without SSH-ing into the VPS
> and running `docker logs | grep`.
>
> **Secondary goal (nice-to-have):** be notified automatically when something
> breaks, instead of having to go and look.

---

## 0. TL;DR

| Question | Tool | Priority | Status |
|---|---|---|---|
| "Show me the logs, let me search them" | **Loki + Promtail + Grafana** | 🔴 **core** | ✅ **done** (Phase 1, acceptance test passed) |
| "Why did *this* request fail?" | Structured JSON logs + request-ID | 🔴 **core** | ✅ **done** (Phase 0) |
| "Is it slow / degrading? What's the error rate?" | Prometheus + Grafana | 🟠 high | 🟡 `/metrics` exists, nothing scrapes it |
| "Tell me before I notice" | Grafana alert rules | 🟢 optional | ❌ not started |
| "Is the whole VPS unreachable?" | External watchdog (off-box) | 🔵 last | ❌ not started |
| "Which exception, grouped, with stack trace?" | Sentry | 🟢 optional | ❌ not started |
| Build & deploy | GitHub Actions | — | ✅ already done (`.github/workflows/deploy.yml`) |

**One-line plan:** make the logs structured → put Loki + Grafana on the VPS →
add Prometheus for the numeric side → add alerts later, cheaply, in the Grafana
that already exists.

---

## 1. Tool glossary — what is what

A note on framing, because it caused confusion early on. There are two distinct
capabilities, and they are **not** substitutes:

- **Observability (pull)** — a UI you *open* to ask questions: "all errors from
  `backend-prod` in the last hour", "why did this upload fail", "is latency
  creeping up?". **This is the actual goal.** → Loki + Grafana + Prometheus.
- **Alerting (push)** — something *messages you* when a threshold trips.
  A thin layer **on top of** observability, not a replacement for it. Once
  Grafana exists, this is a config screen rather than a project — which is
  exactly why it does not need to come first.

### The tools

- **Jenkins** — CI/CD (build, test, deploy). **Nothing to do with monitoring.**
  **Not needed**: `.github/workflows/deploy.yml` already covers CI/CD. Adding
  Jenkins would mean maintaining a second CI server for zero gain.

- **Kibana** — the UI of the **ELK** stack (Elasticsearch + Logstash + Kibana),
  for log search and visualisation. This is the thing originally in mind, and the
  *concept* is right — but ELK is heavy (Elasticsearch alone wants 2–4 GB RAM and
  real operational care). **We use Loki instead**: same job, ~10× lighter, and it
  renders inside Grafana so there is a single UI for both logs and metrics.

- **Loki** — "Prometheus for logs". Indexes only labels (service, env, level),
  not full text — hence cheap. **This is our Kibana.**

- **Promtail** — the agent that tails Docker container logs and ships them to
  Loki. Collects from *every* container: backends, Docling, n8n, Postgres, Nginx.

- **Prometheus** — pull-based time-series DB. Scrapes a `/metrics` HTTP endpoint
  every N seconds and stores numbers (requests/s, latency percentiles, error
  rate, JVM heap, DB pool usage). Answers "is it *degrading*?", which logs alone
  cannot.

- **Grafana** — the single pane of glass. Dashboards over Loki **and** Prometheus,
  plus alerting when we want it.

- **node-exporter / cAdvisor / postgres-exporter** — small Prometheus exporters
  for host resources (CPU/RAM/**disk**), per-container usage, and Postgres stats.

- **Sentry** — application error tracking: stack traces grouped and deduplicated
  per exception, with request context. Complements the rest — Prometheus says
  *"error rate is 3%"*, Loki lets you *find* the lines, Sentry says *"NPE at
  `UserConsumptionService.kt:87`, 412 occurrences, first seen Tuesday"*.

- **Uptime Kuma / UptimeRobot / Healthchecks.io** — black-box "is it reachable?"
  pingers. Only meaningful **off-box** (see §6). Lowest priority.

---

## 2. What we already have (verified in code)

| Item | Location | Notes |
|---|---|---|
| Micrometer + Prometheus registry | `src/main/kotlin/com/bm/backend/Application.kt:63` | `MicrometerMetrics` plugin installed |
| `/metrics` endpoint | `Application.kt:123` | Returns `prometheusMeterRegistry.scrape()` |
| `/health` with real DB check | `Application.kt:131` | Returns 503 + `"degraded"` when DB unreachable — good pattern |
| Docker healthchecks | `BmInfra/docker-compose.yml` | On all backends + Docling services |
| `restart: unless-stopped` | `BmInfra/docker-compose.yml` | On every service |
| CI/CD | `.github/workflows/deploy.yml` | GitHub Actions |

**The gap:** nothing consumes any of it, and the logs themselves are unstructured.

---

## 3. Known issues to fix along the way

Found while reviewing the code. These are prerequisites, not polish — Loki is
only as useful as what we feed it.

1. **No `logback.xml` at all.** Nothing in `src/main/resources/`, so we run on
   Logback's default console pattern. Shipping unstructured text into Loki gets
   you a slightly nicer `grep`, not queryable observability.

2. **`StatusPages` swallows every exception without logging it.**
   `Application.kt:67` catches `Throwable`, returns `cause.message` to the
   client, and **never logs the stack trace**. This is both:
   - a total blind spot — crashes are invisible server-side, so they will be
     invisible in Grafana too; and
   - an information leak — DB errors, SQL fragments and file paths are returned
     to the API caller.

3. **No request correlation ID.** A single bill upload crosses
   Nginx → backend → Docling → n8n → back. Without an `X-Request-Id` propagated
   across hops and held in the MDC, correlating a failure across four services is
   guesswork. **This is the single highest-value feature for log analysis** — it
   is what turns "a wall of log lines" into "the story of one request".

4. **`/metrics` is unauthenticated and rate-limited.**
   - Nginx (`BmInfra/nginx/nginx.conf`) does not proxy `/metrics` — good — but
     `backend-prod` publishes port `8081` straight to the host, so it is
     internet-reachable if the firewall allows 8081. It leaks endpoint names and
     traffic volumes.
   - It also sits behind the global `RateLimit` (`Application.kt:76`,
     100 req/min), which a scraper plus normal traffic can trip.

5. **`/health` conflates liveness and readiness.** It returns 503 when the DB is
   down — correct for *readiness*, but Docker's healthcheck uses it, so a
   transient DB blip makes Docker restart a perfectly healthy process. Split:
   - `/health/live` → "the process responds", no dependency checks → Docker
   - `/health/ready` → "DB + deps reachable" → load balancer / alerting

6. **Unbounded log files.** `BmBackend/docker-compose.yml` mounts
   `./logs:/app/logs`; fine locally, but unrotated files on the VPS grow forever.
   Prefer stdout-only (Promtail collects it) plus Docker log rotation.

---

## 4. Disk growth & retention policy

Worth its own section because it is the one failure mode that turns
"I'll look when I'm curious" into a total outage.

### Do NOT "flush logs weekly"

A weekly wipe is a blunt instrument that solves the wrong problem:

- **It destroys the main use case.** The most common real question is *"when did
  this start?"*, and the answer is frequently *"about ten days ago"*. A 7-day
  window means every investigation spanning more than a week hits a wall.
- **Logs are not actually the disk risk here** — see below.

Use **time-based retention with a size cap** instead: automatic, no cron job to
forget about, and it degrades gracefully rather than cliff-edging.

### What actually fills the disk

| Source | Bounded today? | Fix |
|---|---|---|
| **Docker `json-file` logs** | ❌ **unbounded by default** — the classic killer | `max-size: 10m`, `max-file: 3` on every service |
| `docling-temp` / `docling-uploads` | ❌ no eviction policy — uploaded PDFs + intermediates accumulate | scheduled cleanup of files older than N days |
| Loki chunks | ❌ until configured | 30-day retention + size cap |
| Prometheus TSDB | ❌ until configured | 90-day retention + size cap |
| `docling-models` | ✅ bounded, but large | leave alone |
| Postgres WAL | ✅ unless archiving is misconfigured | monitor |

### Agreed budget (VPS is 720 GB NVMe)

- **Prometheus: 90 days** (~10–20 GB) — long history is what makes "was it also
  slow last month?" answerable
- **Loki: 30 days** (~10–30 GB depending on volume)
- Total ≈ **30–50 GB, under 7% of the disk**

### And still alert on disk

Retention policies cover the growth you *predicted*. The disk alert (>85%) is the
backstop for the growth you didn't. This is the one alert worth having even if we
build nothing else in the alerting phase.

---

## 5. Branch & environment strategy

**This goes into both `qa` and `main`.** The reasoning:

- If it only exists on `main`, **QA stops being a faithful rehearsal of PROD**,
  and the monitoring code itself ships to production untested.
- **The code is identical on both branches. Only configuration differs**, via env
  vars — never branch-specific code:
  - `BM_ENV=qa|prod` → a Prometheus/Loki label and a Sentry environment
  - `LOG_LEVEL=DEBUG` (qa) / `INFO` (prod)
  - `LOG_FORMAT=text` (local dev) / `json` (qa + prod)
- **Workflow:** develop on `qa` → validate against the QA stack (`backend-qa`,
  `n8n-qa`, `bm_qa`) → merge to `main`. Same as the existing process.
- **One monitoring stack, not two.** Prometheus scrapes *both* backends; Loki
  ingests from both; Grafana filters by the `env` label. Duplicating the stack
  per environment doubles resource use for no benefit.
- If/when alerting is added, **severity must differ**: QA notify-only, PROD
  paging. Otherwise the channel gets muted within a week.

---

## 6. Hosting decision

### Production VPS (existing) — 12 vCore / 24 GB RAM / 720 GB NVMe SSD

The full stack — Prometheus ~200 MB, Grafana ~150 MB, Loki ~150 MB, Promtail
~50 MB, exporters ~50 MB — totals **~600–800 MB, roughly 3% of RAM**. Disk is a
non-issue.

**Decision: self-host the entire observability stack on the production VPS.**
Grafana Cloud is unnecessary and would add an external dependency plus a
data-egress path for logs that may contain customer data.

💡 With 12 vCores there is headroom for a **15s Prometheus scrape interval**
(instead of the usual 30–60s) for finer-grained latency graphs.

### The external watchdog — deferred, and probably not a VPS

An uptime monitor is only meaningful if it runs **off-box**: if the VPS dies, a
monitor on that VPS dies with it and you get silence, which is indistinguishable
from "everything is fine".

But this is **the lowest-priority item**, and when we get to it, a second server
is the *most* work of all the available options:

| Option | Notes |
|---|---|
| **UptimeRobot** (uptimerobot.com) | 50 monitors, 5-min interval, Telegram alerts, free, no card. ~15 min setup. |
| **Healthchecks.io** | 20 checks, free, no card. **Push-based**: the VPS pings out; silence alerts. Works with nothing publicly exposed — relevant given the corporate-network constraints in `MULTI_ENV_DEPLOYMENT.md`. Also the right tool for **backup/cron job** monitoring. |
| **Cloudflare Workers + Cron Triggers** | Free, **no credit card**, cron down to 1 min. ~30 lines to curl `/health/live` and post to Telegram. More reliable than any free VPS. |
| **GitHub Actions `schedule`** | Zero new infrastructure. Caveat: scheduled runs are often delayed 5–15 min, so it is a coarse safety net. |
| **Google Cloud Always Free** `e2-micro` | 1 GB, US regions only. A real free VPS if we insist on self-hosting Uptime Kuma. |
| **Oracle Always Free** | 4 ARM cores / 24 GB, best free VPS on paper — but signup/email validation is currently failing, and ARM capacity is usually exhausted. **Not worth further time.** |

**Recommendation when we reach this phase:** UptimeRobot or a Cloudflare Worker.
Do not hunt for a free VPS.

---

## 7. Target architecture

```
════════════════════ PRODUCTION VPS (12 vCore / 24 GB / 720 GB) ══════════════
                            ┌────────────┐
                            │   Nginx    │
                            └─────┬──────┘
             ┌────────────────────┼────────────────────┐
             ▼                    ▼                    ▼
      backend-prod          backend-qa            n8n-prod/qa
      docling-api           docling-price-tables   postgres
             │ stdout (JSON) + /metrics                │
             └────────────────────┼────────────────────┘
                     ┌────────────┴────────────┐
                     ▼                         ▼
              ┌────────────┐            ┌────────────┐
              │  Promtail  │            │ Prometheus │◄── node-exporter
              └─────┬──────┘            └─────┬──────┘    cAdvisor
                    ▼                         │           pg-exporter
              ┌────────────┐                  │
              │    Loki    │                  │
              └─────┬──────┘                  │
                    └──────────┬──────────────┘
                               ▼
                        ┌────────────┐
                        │  GRAFANA   │  ◄── the single pane of glass
                        └─────┬──────┘
                              │ (optional, later)
                              ▼  alerts ──► Telegram / email
═════════════════════════════════════════════════════════════════════════════
   (much later, optional)  external watchdog ──► pings /health/live from off-box
```

---

## 8. Alerts — for when we get to Phase 4

Deliberately short. An alert that fires often and gets ignored is worse than no
alert: it trains you to ignore the real one.

**Worth having even if we build nothing else:**
- **Disk usage > 85%** (see §4)

**Then, if wanted:**
- HTTP 5xx rate > 1% over 5 min (PROD)
- `/health/ready` failing > 3 min (DB unreachable)
- p95 latency > 2 s over 10 min
- JVM heap > 85% sustained
- Container restarted in the last 5 min
- Hikari pool active connections > 80% of max
- Docling / n8n healthcheck failing (degrades features, doesn't kill the app)
- Postgres backup job hasn't reported in (dead man's switch)

---

## 9. Task list

Ordered by *your* priority: get a usable log/observability framework first.

### Phase 0 — Make the logs worth collecting ✅ DONE (branch `qa`)
**Prerequisite for everything else.**

- [x] Add the `logstash-logback-encoder` dependency (JSON output) — `build.gradle.kts`
- [x] Add `src/main/resources/logback.xml`
  - [x] Console appender only (Docker/Promtail collects stdout)
  - [x] JSON encoder when `LOG_FORMAT=json`, human-readable pattern otherwise
  - [x] Level from `LOG_LEVEL` env var (default `INFO`)
  - [x] Include `env`, `service`, `requestId` fields
  - [x] Quiet noisy loggers (Netty, Exposed, Hikari, Postgres, Flyway, Google)
- [x] **Request-ID correlation** — `plugins/RequestId.kt`
  - [x] Read `X-Request-Id` or generate a UUID
  - [x] Sanitize the inbound value (log-forging / unbounded length defence)
  - [x] Put it in the SLF4J MDC via `CallLogging` for the whole call
  - [x] Echo it back in the response header
  - [x] Propagate it from `ExternalApiService` → Docling, n8n (`DefaultRequest`)
  - [x] `proxy_set_header X-Request-Id $request_id;` in `BmInfra/nginx/nginx.conf`
- [x] **Fix `StatusPages`** (`Application.kt`)
  - [x] Log the exception **with stack trace** + request ID
  - [x] Return a generic message + request ID (no more leaking `cause.message`)
  - [x] `ValidationException` mapped to 400 with its caller-facing message
- [x] Add `BM_ENV` / `SERVICE_NAME` (`observability/DeploymentInfo.kt`); metrics
      carry `env` + `service` common tags, logs carry the same fields
- [x] Docker log rotation (`max-size: 10m`, `max-file: 3`) on **all 8 services**
      (moved up from Phase 1 — it is the main unbounded-disk risk, see §4)
- [x] Suppress `/health` + `/metrics` from request logs (polled continuously)
- [x] `deny all` for `/metrics` in `nginx.conf` (moved up from Phase 2)
- [x] Tests — `src/test/kotlin/com/bm/backend/ObservabilityTest.kt`, 10/10 green,
      no Docker required
- [x] ✅ **Blocker cleared.** `./gradlew build` runs off the corporate proxy and
      `logstash-logback-encoder:8.0` resolves from Maven Central; the `shadowJar`
      also builds inside the Docker image. `LOG_FORMAT=json` verified in a real
      container: `service`, `env`, `requestId`, `method`, `path` and
      `stack_trace` all emitted as JSON fields.
- [x] Plain-text access log lines — Ktor's default `CallLogging` formatter emits
      ANSI colour codes which, under `LOG_FORMAT=json`, were embedded in the JSON
      `message` field (garbage in Grafana, broken exact-match Loki queries).
      Replaced with an explicit formatter + regression test.
- [ ] Review existing log statements: add context (user id, job id, bill id)
- [ ] Merge `qa` → `main`

> ⚠️ **Pre-existing, unrelated:** `./gradlew build` still fails on two
> `GrantedUsersServiceTest` cases (`Please call Database.connect()`). They hit a
> real `deleteGrant` transaction while the other 45 DB-backed tests skip without
> Docker. Confirmed present on `qa` at `fc2c2c9`, so it predates this work and
> needs its own fix — a test-isolation bug, not an observability one.

### Phase 1 — The log framework: Loki + Promtail + Grafana ✅ DONE
**This is the "Kibana" deliverable.**

- [x] Add `loki` service to `BmInfra/docker-compose.yml` + config, **30-day retention + size cap**
      (`retention_enabled` + `delete_request_store` set, without which
      `retention_period` is inert and the disk grows anyway)
- [x] Add `promtail` service (read-only Docker socket, persisted positions)
- [x] Label streams by `container`, `service`, `env`, `level` — `requestId` is
      deliberately structured metadata, not a label, to avoid one stream per request
- [x] Parse the backend's JSON logs into queryable fields in Promtail
- [x] Re-attach `stack_trace` to the log line before the `output` stage. The
      `output` stage *replaces* the line with a single field, so exceptions were
      reaching Loki as a one-line message with the trace silently dropped
- [x] Add `grafana` service, persistent volume, **non-default admin password**
- [x] Add Loki as a Grafana datasource
- [x] Keep Grafana off the public internet (binds to `127.0.0.1` + SSH tunnel)
- [x] Build a "BM Logs" dashboard + a request-trace dashboard
- [x] Nginx JSON access logs (`log_format bm_json`) so the edge hop is
      correlatable too, with a matching Promtail job
- [x] **Acceptance test — PASSED.** Full stack run locally (Postgres, both
      backends, Nginx, Loki, Promtail, Grafana). A request through Nginx produced
      one id shared by the Nginx access line and the backend line, retrievable
      with `{job=~"bm-.+"} | requestId=\`<id>\``. A triggered 500 arrived in Loki
      with its complete 4 kB stack trace. `/metrics` returns 403 on every vhost.
      A caller-supplied `X-Request-Id` is correctly overwritten at the edge.


### Phase 2 — Metrics: Prometheus 🟠
- [ ] Protect `/metrics` first
  - [ ] Move it outside the global `RateLimit` scope
  - [ ] Bearer token (`METRICS_TOKEN`) or bind to a separate internal port
  - [x] Explicit `deny` for `/metrics` in `nginx.conf` *(done in Phase 0)*
  - [ ] Close host ports 8081/9081 on the VPS firewall; route public traffic via Nginx
- [ ] Split the health endpoints
  - [ ] `GET /health/live` — no dependency checks
  - [ ] `GET /health/ready` — current DB check logic
  - [ ] Keep `GET /health` as an alias for `/health/ready` (backwards compatible)
  - [ ] Point Docker healthchecks at `/health/live`
- [ ] Add `prometheus` service + `prometheus.yml`, **90-day retention**, 15s scrape
  - [ ] Targets: `backend-prod:8081`, `backend-qa:8081`, labelled by `env`
- [ ] Add `node-exporter` (host CPU / RAM / **disk**)
- [ ] Add `postgres-exporter`
- [ ] Add `cAdvisor` (optional — per-container usage)
- [ ] Add Prometheus as a Grafana datasource
- [ ] Import dashboards: JVM/Micrometer, Ktor HTTP, Node Exporter Full, Postgres
- [ ] Custom BM dashboard: request rate, error rate, p95 latency, upload throughput, job queue depth
- [ ] Add business metrics in the backend: bills processed, Docling failures, n8n webhook failures, job durations

### Phase 3 — Disk hygiene 🟠
- [ ] Scheduled cleanup for `docling-temp` / `docling-uploads` (files older than N days)
- [ ] Verify Loki + Prometheus retention actually enforce their size caps
- [ ] Remove or rotate the `./logs:/app/logs` host mount on the VPS
- [ ] Grafana panel: disk usage trend over 30 days

### Phase 4 — Alerting 🟢 *(optional — small increment once Grafana exists)*
- [ ] Configure a Telegram (or email) contact point in Grafana
- [ ] **Disk > 85%** rule first — the one that matters most
- [ ] Add the §8 rules incrementally, PROD-paging / QA-notify
- [ ] Test each rule by actually triggering it
- [ ] Write `docs/RUNBOOK.md`: what to do for each alert

### Phase 5 — Optional extras 🔵
- [ ] **Sentry** for grouped exception tracking (`environment` from `BM_ENV`, `release` from the Git SHA in `deploy.yml`, request ID attached)
- [ ] **External watchdog** — UptimeRobot or a Cloudflare Worker on `/health/live` (see §6)
- [ ] Expose `/health/live` publicly via Nginx (leaks nothing); keep `/health/ready` internal
- [ ] Certificate-expiry alert once a domain + TLS exists
- [ ] Public `/status` page
- [ ] Quarterly "break it on purpose" drill

---

## 10. Open decisions

| Decision | Options | Status |
|---|---|---|
| Metrics/logs hosting | Self-hosted vs Grafana Cloud | ✅ **RESOLVED — self-host on the prod VPS** (~3% of RAM) |
| Log retention | Weekly flush vs time-based | ✅ **RESOLVED — 30d Loki / 90d Prometheus, size-capped.** No weekly flush (see §4) |
| Grafana exposure | localhost + SSH tunnel vs Nginx + auth | ⏳ open |
| External watchdog | UptimeRobot / Cloudflare Worker / skip | ⏳ open, **deferred to Phase 5** |
| Sentry | Yes / no; hosted vs self-hosted | ⏳ open |
