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

`POST /api/v1/teach-me/references` is also QA-only and independently authorizes
each request. It accepts one nonempty raw PDF (`application/pdf`) or PNG
(`image/png`), at most 2 MiB (2,097,152 actual bytes). Send exactly one
`Content-Type` (case-insensitive, no parameters) and exactly one
`X-Teach-Reference-Filename` (canonical unpadded Base64url, at most 340 wire
characters and 255 strictly decoded UTF-8 bytes). Names need a nonblank base
and a final ASCII `.pdf` or `.png` extension matching the type, with no slashes,
controls, bidi controls or BOM. Queries and `Content-Encoding` are not accepted.
The only success is a `200` with `{"attachment":{"kind":"reference_pdf",`
`"filename":"apoyo.pdf","sizeBytes":123}}` (or `reference_png`). This is a
receipt for one validated request, not a message, resource, or grant for another
upload. Invalid metadata/empty body returns `400 invalid_message`; excess bytes
return `413 upload_too_large`; unsupported type, encoding or structure returns
`415 unsupported_reference`.

BmWeb's Node endpoints (`/api/teach-me/messages` and `/api/teach-me/references`) use a Pages API boundary
with body parsing disabled so it can distinguish raw duplicate header lines.
The proxy streams counted original bytes to the configured QA backend, without
redirects, forwarding only the bearer, canonical media type and filename header.
It also bounds the JSON receipt to 4096 bytes and checks the exact shape and
match against the upload. Both layers enforce the file-specific byte limits:
5 MiB for Excel, 2 MiB per reference. Ktor 2.3.12 reads a raw `ByteReadChannel`
into a fixed-size buffer only after authorization and upload admission. A
one-byte excess probe does not retain the excess in the file buffer or drain
the remaining body. The proxy shares four slots across the two routes and
uses a 15-second deadline per request. All Enséñame Ktor POST routes share four private
authorization workers with direct handoff (no queue) and four independent upload permits. Its single
15-second deadline starts when authorization is admitted and also covers upload
and validation (including inspection and response encoding when requested). Saturation returns `429` without opening the body; authorization
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
For message receipts JDK StAX reads only `[Content_Types].xml` and `_rels/.rels`,
with DTD and external resolution disabled. No workbook sheets, formulas, or
values are interpreted in `/messages`.

References use byte-only envelope checks. PDF is limited to `%PDF-1.0`–`1.7`
with a line-terminated header, a `startxref` pointing to a classic `xref` table
token and a final `%%EOF` (only spaces, tabs or CR/LF may follow). PDF 2.0 and
xref streams are out of profile; no objects or pages are opened. PNG requires
the full signature, at most 256 bounded chunks with CRC32, an initial unique
13-byte `IHDR` with dimensions 1–4096, contiguous `IDAT` chunks with data, and
a final empty `IEND` with no trailing bytes. Other chunks are opaque. No PNG
pixels are decoded. These are pilot checks, not guarantees of safety, full
document integrity, or renderability.

Dependency review for this slice: Commons Compress 1.28.0 plus resolved Commons
IO 2.20.0, Codec 1.19.0, and Lang 3.18.0 are Apache-2.0 (JAR LICENSE/NOTICE).
Review of Apache security pages and GitHub's Maven advisories found no advisory
matching these selected versions at implementation time; this is not a full
backend audit. No Commons FileUpload dependency is used.

### Inspect a workbook (controlled client, QA only)

`POST /api/v1/teach-me/inspect-workbook` accepts the same Firebase bearer,
canonical singleton XLSX `Content-Type`, singleton `X-Teach-Workbook-Filename`
(at most 340 wire bytes and 255 strictly decoded UTF-8 bytes), and nonempty raw
body as `/messages`. The limit is **5,242,880 actual bytes**, independent of
`Content-Length`. No query, `Content-Encoding`, multipart, attachment ID or URL.
Every request rechecks current admin access and nomination. There is no new web
proxy or UI. A prior receipt is not a resource: the client must resubmit the
original bytes explicitly (reselect the file if the client discarded it).

The only success is complete `200 application/json; charset=UTF-8`, `no-store`:

```json
{"attachment":{"kind":"workbook","filename":"sintetico.xlsx","sizeBytes":123},"inspection":{"version":1,"dateSystem":"1900","sheets":[{"name":"Ejemplo","state":"visible","headers":["A2"],"cells":[{"a1":"A2","kind":"text","value":"Tarifa"},{"a1":"A3","kind":"number","value":"0"},{"a1":"C3","kind":"blank"},{"a1":"D3","kind":"formula"}]}]}}
```

The narrowed Transitional SpreadsheetML profile resolves only the internal
workbook, worksheets and optional shared strings. It preserves sheet order,
names and `visible`/`hidden`/`veryHidden` state, and requires explicit increasing
canonical A1 coordinates. An absent cell is omitted; explicit blanks and formulas
have no `value`; an empty string is text. Boolean values are JSON booleans;
numbers, explicit dates, text and classic error tokens are strings. Numeric
lexemes (max 64 bytes) and date text are not converted; a date-formatted serial
remains a number and `dateSystem` (`1900`/`1904`) does not change it. Headers are
candidate non-whitespace text coordinates on the first row with a nonblank
literal (ignoring formula/error cells), not a semantic determination.

Inspection runs inside BmBackend on the same request buffer. Each ZIP member is
inflated/verified once, including opaque members; final integrity checks precede
serialization. Only selected XML is parsed: **262,144 bytes per part, 2,097,152
bytes total**, within the existing **33,554,432** total inflated ZIP bytes and
**256** ZIP members. Parsed XML is strict UTF-8 (optional initial BOM), with no
DTD, external resolution, `xml:base`, XInclude or processing instructions, and
at most **16** levels, **32** combined attributes/namespace declarations per
element and **100,000** StAX events. The model allows **8** sheets, **1,024**
shared strings, **512** explicit rows per sheet and **1,024** explicit cells
overall, with **1,024 decoded UTF-8 bytes** per cell string/shared entry. The
whole encoded response, including escapes and receipt, must fit in **65,536
UTF-8 bytes** before success is sent. Links/objects stay opaque. No macro or
formula execution, cached formula values, storage, external service, or model
call; no payload logging. These are pilot limits, not general Excel support.

Errors are atomic and generic: `400 invalid_message` (metadata/empty body),
`413 upload_too_large` (budget exceeded), `415 unsupported_workbook` (type,
encoding, integrity or profile), `401`/`403` (access), `404` outside QA, `408`
(deadline) and `429` (admission). Existing receipt/proxy contracts do not change.
See the [approved coordination spec](../docs/specs/inspect-workbook.md).

Use only synthetic files in local QA. The browser requires an Excel and supports
one optional PDF and one optional PNG. It validates PDF → PNG → Excel in separate
requests and adds one local entry only after all matching receipts arrive. A
retry validates the entire selection again; this is UI atomicity, not a server
transaction. The optional comment is never sent. Neither layer stores, logs,
or exposes the file for retrieval; bytes are discarded after the request. The
browser retains only the confirmed comment and presentation metadata for the
lifetime of the same view/account, not a retrievable history. See the coordination
specs `docs/specs/teach-workbook-attachment.md` and
`docs/specs/teach-reference-attachments.md` for the approved boundaries.

### Clarify workbook rules (isolated local QA pilot)

`POST /api/v1/teach-me/clarifications` is only mounted when `BM_ENV=qa` and
`BM_TEACH_CLARIFY_LOCAL=true`. It is off by default; do not enable in hosted QA.
BmWeb needs its own `NEXT_PUBLIC_TEACH_CLARIFY_LOCAL=true` opt-in. The Ktor route
rechecks the same Firebase, current admin and nomination guard before reading
either turn and shares the existing four auth workers, four upload permits and
15-second per-request deadline (including local adapter and encoding). An
observed Netty peer close cancels the request; an unobserved FIN is not probed.

Accepts a single `Content-Type: application/vnd.bm.teach-clarification` with no
parameters, query or `Content-Encoding`. The unsigned four-byte big-endian
prefix gives the strict UTF-8 JSON tuple length (1–32 KiB). The exact tuple is
`[1,"ask",xlsxBytes]` or `[1,"reply",xlsxBytes,pdfBytes,pngBytes,workbookSha256,answer]`.
Raw XLSX bytes follow, then PDF and PNG in that order if nonzero. No names,
IDs, base64 attachments or URLs. Byte limits: XLSX one byte to 5 MiB,
PDF/PNG up to 2 MiB each (zero means absent), 9 MiB + 32 KiB + 4 bytes total,
and a nonblank answer of at most 4 KiB of decoded UTF-8. Segment lengths are
checked before allocation; truncation and the first excess byte are rejected
without draining. Errors are non-cacheable: `400` framing/text, `413` exceeded
budgets, `415` unsupported type/encoding/structure, `409` continuity, `401`/`403`
access, `404` unavailable, `408` deadline, `429` busy and generic `500` internal
failure. The proxy sanitizes replies and may return generic `502`.

The inspector's typed structural result identifies exactly two literal
`Precio fijo` text headers in visible sheets, in inspection order. For this
pilot only, the header heuristic uses the first row with a visible nonblank
literal: standard hidden flags, zero row height and zero column width exclude
cells. Overlapping or malformed column ranges, unsupported default-hidden
sizing, collapsed outlines, and ambiguous visibility yield `not_applicable`
on ask and `409` on reply. These checks use the same bounded worksheet parse and do not change
`/inspect-workbook` headers, grammar or wire. Success is
closed `200 application/json; charset=UTF-8` (`no-store`, up to 4 KiB):
`{"kind":"question","workbookSha256":"…","candidates":[{"sheet":"Tarifas","a1":"B2"},{"sheet":"Tarifas","a1":"C2"}]}`,
`{"kind":"not_applicable"}`, or `{"kind":"acknowledged"}`. The reply resubmits the
same XLSX; the server computes SHA-256 on the received chunks and reconstructs
candidates. A digest is not a grant or evidence of a prior request. The fixed
in-process adapter gets only verified sheet ordinals and A1 coordinates,
the reply as inert data and read-only views of optional validated PDF/PNG byte
segments. Only the expected decision for the stage is accepted. No XLSX bytes
or credentials cross the port; there is no attachment interpretation, provider,
rule creation, import, persistence or payload logging. Bytes are request-local.
Existing messages, references and inspection wires are unchanged.

JDK 17 focused gate:

```sh
./gradlew test --tests '*TeachMeAccessRoutesTest' --tests '*FirebaseRouteAuthTest' \
  --tests '*TeachWorkbook*Test' --tests '*TeachReference*Test' \
  --tests '*TeachClarificationRoutesTest' --tests '*TeachClarificationServiceTest'
```

With Node 20 also on `PATH`, run the joint gate from BmBackend using the BmWeb
checkout with installed dependencies:

```sh
BM_TEACH_CLARIFY_TEST_WEB="$(cd ../BmWeb && pwd)" ./gradlew test --rerun \
  --tests '*TeachClarificationRoutesTest.candidate Pages proxy and Ktor share the same two synthetic turns over loopback' --info
```

An ephemeral Netty listener mounts the candidate guard with a controlled token
verifier and in-memory admin repository. The test drives an ephemeral Node HTTP
server running the candidate Pages handler, then asserts both turns and exact
reference bytes at the local adapter. This does not certify real Firebase/QA
access, deployed infrastructure, a model provider or browser layout. The full
backend gate remains `./gradlew build`.

### Draft a demo supplier skill (isolated local QA pilot)

Default off. Requires `BM_ENV=qa`, `BM_TEACH_CLARIFY_LOCAL=true`, and
`BM_TEACH_DRAFT_LOCAL=true` on the backend, plus `NEXT_PUBLIC_APP_ENV=qa`,
`NEXT_PUBLIC_TEACH_CLARIFY_LOCAL=true`, and `NEXT_PUBLIC_TEACH_DRAFT_LOCAL=true`
in BmWeb. Do not configure this in hosted QA or production. The author still
needs a current admin grant and nomination on every POST before the body is
opened. It shares the same auth/stream permits, 15-second deadline and
observed-close behavior as clarification; no new endpoint or context store.

Use the existing `/api/v1/teach-me/clarifications` route with one
`Content-Type: application/vnd.bm.teach-supplier-draft`, no parameters,
encoding, query or filename headers. The four-byte big-endian prefix gives
the strict UTF-8 tuple length (1–32 KiB). The v1 tuples are:

```text
[1,"ask",xlsxBytes]
[1,"draft",xlsxBytes,workbookSha256,supplierKey,answer]
[1,"revise",xlsxBytes,workbookSha256,supplierKey,priorRevisionId,answer]
```

Only the raw XLSX segment follows, 1 byte to 5 MiB, with exact end. No PDF/PNG
or other attachments in this wire. All three turns require the exact bytes
of [`ensename-demo.xlsx`](src/test/resources/ensename/ensename-demo.xlsx).
The fixture has one `Tarifas` worksheet with two literal `Precio fijo` headers
at `B2` and `C2`, and no prices, formulas or external links. The backend pins
its byte digest and uses the existing bounded validator and visible-header
selector on every turn, not the filename or any retained data. A local rename
is harmless; resaving or substituting a commercial workbook is not supported.

The answer must be `La columna B` or `La columna C` after trimming exterior
ASCII spaces, still subject to the 4 KiB UTF-8 answer cap. The only supplier
key is `demo-electricidad`. `ask` returns the existing `question` schema (or
`not_applicable` if the expected evidence is absent). `draft`/`revise` return
`{"kind":"draft","draft":…}` (closed v1 JSON, max 4 KiB), with `schemaVersion`,
`supplierKey`, `workbookSha256`, `rule: {kind:"fixed_price_column",header:{sheet:"Tarifas",a1:"B2"}}`
(or `C2`), and `revisionId`. Both digests are 64 lowercase hex characters.
The revision ID is SHA-256 of UTF-8 fields separated by LF, without a final
LF: `bm.teach.supplier-draft/v1`, supplier key, workbook digest, rule kind,
sheet and cell. A correction must name the ID for the opposite column, not
an arbitrary draft. Revisions are content fingerprints, not grants, proof
of prior requests, monotonic versions or stored objects.

The fixed in-process adapter gets only verified ordinal/A1 descriptors and
the closed selection; disagreement is an internal failure. No model or
provider, price extraction, preview, activation, PDF or catalog calls, state,
storage or payload logging. Out-of-profile workbook/digest/continuity gives
`409`; invalid selection, same-column correction, unknown supplier or frame
gives `400`; the existing sanitized budget, access and service errors apply.
Normal clarification keeps its existing wire and opaque reference behavior.

Add `--tests '*TeachSupplierDraft*Test'` to the focused JDK 17 gate. With
Node 20 on `PATH`, run the existing joint test above and separately run:

```sh
BM_TEACH_CLARIFY_TEST_WEB="$(cd ../BmWeb && pwd)" ./gradlew test --rerun \
  --tests '*TeachSupplierDraftRoutesTest.candidate Pages proxy and Ktor draft and revise over loopback' --info
```

That gate uses the versioned fixture, candidate Pages handler, Netty and a
controlled verifier/admin repository on ephemeral loopback. It checks the
question, both content-derived IDs, invalid continuity and live admin
revocation before the denied body is opened. Vitest runs without inherited
credentials or Vite `.env` files; the test does not certify hosted QA, real
Firebase, a real model or browser layout.

### Preview draft prices (isolated local QA, V2)

Default off. Requires all the QA/clarification/demo opt-ins above and, separately,
`BM_TEACH_PREVIEW_LOCAL=true` on BmBackend and `NEXT_PUBLIC_TEACH_PREVIEW_LOCAL=true`
in BmWeb. Do not configure this in hosted QA or production. The same guard,
four auth workers, four upload permits, 15-second deadline and observed-close
behavior apply to each POST. No context or execution history is stored.

Use `/api/v1/teach-me/clarifications` with singleton
`Content-Type: application/vnd.bm.teach-draft-preview`; no parameters, encoding,
query, redirects, names or references. The four-byte BE length prefixes a
strict UTF-8 tuple (1–32 KiB), then exactly `xlsxBytes` raw bytes (1–5 MiB):

```text
[1,"prepare",xlsxBytes,profileKey]
[1,"preview",xlsxBytes,profileKey,supplierKey,workbookSha256,revisionId]
```

Select `gesternova-20td-sanitized` explicitly; `supplierKey` is `gesternova`.
Only the exact approved [`gesternova-20td-sanitized.xlsx`](src/test/resources/ensename/gesternova-20td-sanitized.xlsx)
bytes are eligible, including on execution. The separate [oracle](src/test/resources/ensename/oraculo-borrador.md)
documents the expected prices, unit literals, citations and exclusions. These
resources are for tests only; neither is read by the service at runtime. The
bounded workbook inspector supplies literal cell values plus preview-only
visibility and style proof. `Tarifas` row 14 is the header and only visible
`2.0TD` / `Precio por Periodos` rows 16–18 yield offers. Read energy P1 from
`K` and power P1/P2 from `S/T` at verified General format, no conversions.
Energy P2/P3 in `L/M` must be blank or absent; zero is a price, not absence.
No formula caches, non-General styles, localized decimals, or inferences from
surplus fields. Decimals are strings from exact BigDecimal parsing of unsigned
plain literals with 1–6 integer digits (no padding) and up to 12 fractional
digits, no exponent, precision ≤18, scale 0–12, range 0–999999.999999999999.

`prepare` returns `{kind:"draft",draft:<V2>}` where V2 contains `schemaVersion:2`,
the profile and supplier keys, the byte digest, the closed `rule` and `revisionId`.
`preview` reconstructs V2 again and returns `{kind:"preview",profileKey,supplierKey,workbookSha256,revisionId,rows}`.
The ordered rows include proposal/tariff/type with citations and ordered prices
with term/period/unit/exact decimal and header/value citations. Neither response
is an import payload. Both successes are at most 4 KiB and no-store, without
truncation. Wrong profile, selection, supported-but-different XLSX or profile
semantics gives `409`; malformed frame `400`, budget `413`, invalid format
`415`; auth, disabled, deadline and saturation statuses are unchanged. Invalid
upstream success becomes a generic `502` at Pages.

V2 revision ID: lowercase SHA-256 of concatenated UTF-8 fields, each prefixed
with its byte length as ASCII unpadded decimal followed by `:`. Lists begin
with their element count as an ordinary field. Field order:

1. `bm.teach.supplier-draft/v2`, `2`, `profileKey`, `supplierKey`, `workbookSha256`;
2. rule `kind`, `sheet`, `candidateRows`, `fixedRows`, `tariff`, `otherTariff`, `fixedType`, `excludedTypes`;
3. `headers` as `(a1,label)`; `bindings` as `(term,period,unit,header.a1,header.label)`; `absentHeaders` as `(a1,label)`;
4. `styleSha256`, `decimalPolicy`.

The kind and policy names tag the closed extraction/format semantics; change
their version if changing those semantics outside the declarative fields. V1
IDs do not identify a V2 rule. V2 IDs are not a grant, signature, locator or
proof of a prior request. The route calls only in-process inspection/extraction:
no store, PDF/import/catalog/proposal operation, BmPriceAgent, model or provider.

With JDK 17, add `--tests '*TeachDraftPreview*Test'` to the focused gate above.
With Node 20 on `PATH`, run both earlier joints and:

```sh
BM_TEACH_CLARIFY_TEST_WEB="$(cd ../BmWeb && pwd)" ./gradlew test --rerun \
  --tests '*TeachDraftPreviewRoutesTest.candidate Pages proxy and Ktor prepare and preview over loopback' --info
```

This compares the complete oracle at the Pages → Netty boundary and tests wrong
bytes/revision, disabled flags and revocation without opening the denied body.
It uses ephemeral loopback, controlled authorization and no QA stack. The full
backend gate is still `./gradlew build`.
