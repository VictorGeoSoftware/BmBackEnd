# BM Backend - Price Table & Consumption Management API

Kotlin/Ktor REST API for managing price tables and user consumption data with PDF extraction capabilities.

## 🚀 Features

- **Price Table Management**: CRUD operations for electricity price tables
- **User Consumption Processing**: Upload and process consumption PDFs
- **Firebase Authentication**: Token verification with encrypted PII storage (AES-GCM)
- **External API Integration**: 
  - Docling API for PDF data extraction
  - N8N workflows for automated processing
- **PostgreSQL Database**: Production-grade storage with HikariCP connection pooling
- **Flyway Migrations**: Versioned, repeatable schema management
- **Rate Limiting**: Built-in request throttling
- **Health Checks**: DB-aware monitoring endpoints

## 📋 Tech Stack

- **Language**: Kotlin 2.0.21
- **Framework**: Ktor 2.3.12
- **Database**: PostgreSQL 16 with Exposed ORM & HikariCP
- **Migrations**: Flyway 10.21
- **Build Tool**: Gradle 8.13
- **Runtime**: JVM 17
- **Testing**: JUnit 5, Testcontainers (PostgreSQL)

## 🏃 Quick Start

### Local Development

```bash
# Build and run
./gradlew run

# Access API
curl http://localhost:8081/health
```

### Deploy to Server

Deploys are automatic: pushing to `qa` / `main` runs `.github/workflows/deploy.yml`, which rebuilds the backend image in the `BmInfra` Docker Compose project on the VPS. Public API: `https://api.poweredbyadvisors.com` (QA: `https://api.qa.poweredbyadvisors.com`). `QUICK_DEPLOY.md` / `DEPLOYMENT.md` describe the old systemd setup and are kept for reference only.

```bash
chmod +x deploy-remote.sh
./deploy-remote.sh
```

## 📚 Documentation

- **[DEPLOYMENT.md](DEPLOYMENT.md)**: Complete deployment guide
- **[QUICK_DEPLOY.md](QUICK_DEPLOY.md)**: Fast deployment instructions

## 🔌 API Endpoints

### Health & Status
- `GET /health/live` - Liveness: process is up, no dependency checks (used by Docker healthchecks)
- `GET /health/ready` - Readiness: 200 when the database is reachable, 503 otherwise
- `GET /health` - Alias of `/health/ready`, kept for backwards compatibility
- `GET /metrics` - Prometheus metrics. Requires `Authorization: Bearer $METRICS_TOKEN`; returns 401 when `METRICS_TOKEN` is unset
- `GET /` - Service info

### Internal (n8n only)
- `POST /api/v1/batch-process-price-tables`, `POST /api/v1/consumption-report` - Require `X-Internal-Token: $INTERNAL_API_TOKEN`; return 401 when it is missing, wrong or unset. Also denied by nginx.

### Price Tables
- `GET /api/v1/price-tables` - List all price tables
- `GET /api/v1/price-tables/{id}` - Get specific price table
- `POST /api/v1/price-tables` - Create price table
- `PUT /api/v1/price-tables/{id}` - Update price table
- `DELETE /api/v1/price-tables/{id}` - Delete price table

### User Consumption
- `POST /api/v1/user-consumption/upload` - Upload consumption PDF
- `GET /api/v1/user-consumption/jobs/{id}` - Get job status

## 🔧 Configuration

### Required Environment Variables

| Variable | Description | Example |
|----------|-------------|---------|
| `DB_URL` | PostgreSQL JDBC URL | `jdbc:postgresql://localhost:5432/bm_backend` |
| `DB_USER` | Database user | `bm_user` |
| `DB_PASSWORD` | Database password | `secret` |
| `ENCRYPTION_KEY` | 32-byte hex key for AES-GCM PII encryption | `0123456789abcdef...` |

### application.yaml

```yaml
ktor:
  deployment:
    port: 8081
    host: 0.0.0.0

database:
  url: ${DB_URL}
  user: ${DB_USER}
  password: ${DB_PASSWORD}
```

## 🏗️ Project Structure

```
src/main/kotlin/com/bm/backend/
├── Application.kt              # Main application entry
├── database/                   # Database configuration (DataSourceFactory, DatabaseFactory)
├── models/                     # Data models (one file per model)
├── repositories/               # Data access layer (Exposed + raw SQL)
│   └── ports/                  # Repository interfaces (Clean Architecture)
├── routes/                     # API routes
├── security/                   # Firebase auth, AES-GCM encryption
├── services/                   # Business logic (depends on ports)
└── tools/                      # CLI utilities (SQLite-to-Postgres migration)

src/main/resources/
├── application.yaml            # Ktor + DB config
└── db/migration/postgres/      # Flyway migrations (V1-V3)
```

## 🧪 Testing

```bash
# Requires Docker running (Testcontainers spins up PostgreSQL)
./gradlew test

# Tests skip gracefully if Docker is unavailable
```

## 📦 Building

```bash
# Build JAR
./gradlew shadowJar

# Output: build/libs/bm-backend-1.0-all.jar
```

## 🐳 Docker

```bash
# Start PostgreSQL + backend
docker-compose up -d

# The compose file includes:
# - postgres:16-alpine (port 5432) with healthcheck
# - bm-backend (port 8081) with depends_on healthcheck
```

## 🔗 Dependencies

This backend integrates with:
- **Docling API** (port 5000): PDF extraction service
- **N8N** (port 5678): Workflow automation

## 📝 License

Private project - B&M

## 👤 Author

Victor - VictorGeoSoftware
## Enséñame (QA)

`GET /api/v1/teach-me/check-access` is only registered with `BM_ENV=qa`.
Set `BM_TEACH_ME_ADMIN_EMAILS` on the QA backend process to a comma-separated
list of BmWeb admin emails. Accounts must also be present in `admin_users`.
An empty or missing list grants nothing. Restart or recreate the QA backend
to apply list changes; removing general admin access takes effect on the next
request without a restart. No configuration is needed in production.

`POST /api/v1/teach-me/messages` is also QA-only and rechecks the same grant
before opening the request body. It accepts a single nonempty raw `.xlsx` body
(`Content-Type: application/vnd.openxmlformats-officedocument.spreadsheetml.sheet`,
no parameters, encoding or query). Send exactly one `X-Teach-Workbook-Filename`
header: unpadded canonical Base64url of the UTF-8 presentation name (at most
255 bytes). The only success is a `200` validation receipt with `attachment`
containing `kind: "workbook"`, `filename`, and actual `sizeBytes`. It does not
create a resource. Errors are non-cacheable; the view may retry explicitly.

BmWeb's Node endpoint (`/api/teach-me/messages`) uses a Pages API boundary
with body parsing disabled so it can distinguish raw duplicate header lines.
It streams the original bytes with a count and does not forward the client
length or other headers. Ktor 2.3.12 reads a raw `ByteReadChannel` into memory,
never multipart, using a fixed 5 MiB buffer plus a one-byte over-limit probe.
Both layers enforce 5 MiB of actual body bytes. The proxy has four slots and
its own 15-second deadline. Ktor POST uses four private authorization workers
with direct handoff (no queue) and four independent upload permits. Its single
15-second deadline starts when authorization is admitted and also covers upload
and validation. Saturation returns `429` without opening the body; authorization
expiry returns `408` if still connected. The proxy can return `502` if its own
upstream deadline expires first. Read rejection/timeout cancels the reader;
checkpoints cover preflight, ZIP reads, inflation and XML.

A timed-out or disconnected caller discards the authorization result; it
cannot begin an upload later. The Firebase certificate fetch and JDBC admin
lookup are blocking, so the worker remains occupied until the actual call
returns. A stuck dependency can therefore reduce POST capacity. Follow-up:
review dependency-native connect, read and query deadlines separately; this
slice does not change global Firebase/JDBC timeouts or the GET path.

The POST ties caller cancellation to the Netty channel's close notification
and removes its listener at request exit. This covers a peer close once Netty
observes it. With HTTP/1 read backpressure, a FIN behind unread request data
can remain unobserved before authorization completes (confirmed in a loopback
probe). The route does not force additional pre-authorization socket reads to
discover it: doing so would bypass the engine's body backpressure. A guarantee
for unobserved FINs needs a separate transport decision.

On JDK 17 the bounded ZIP preflight counts and validates the real classic ZIP
records (up to 256) **before** constructing Apache Commons Compress 1.28.0's
`ZipFile` over a seekable memory channel. Only STORED and DEFLATED entries are
permitted. The caller uses the bounded raw member stream and checks exact
compressed consumption, actual inflated size, and CRC32; the 32 MiB aggregate
and 256 KiB per identification XML budgets count output bytes, not ZIP claims.
JDK StAX reads only `[Content_Types].xml` and `_rels/.rels`, with DTD and external
resolution disabled. No workbook sheets, formulas, or values are interpreted.

Dependency review for this slice: Commons Compress 1.28.0 plus resolved Commons
IO 2.20.0, Codec 1.19.0, and Lang 3.18.0 are Apache-2.0 (JAR LICENSE/NOTICE).
Review of Apache security pages and GitHub's Maven advisories found no advisory
matching these selected versions at implementation time; this is not a full
backend audit. No Commons FileUpload dependency is used.

Use only synthetic files in local QA. The optional comment exists only in the
browser view and is never sent. Neither layer stores, logs, or exposes the file
for retrieval; bytes are discarded after the request. The browser only keeps
the comment and presentation metadata for the lifetime of the same view/account.
See the coordination spec `docs/specs/teach-workbook-attachment.md` for the
approved runtime, validation, and no-retention boundaries.
