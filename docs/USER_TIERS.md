# BmApp user tiers

BmApp access and BmWeb administration are independent authorization concepts.

- `admin_users` contains the small set of company operators allowed to use
  BmWeb and `/admin/*` endpoints.
- `granted_users` contains accounts allowed to use BmApp and stores their
  product tier.
- Admin status does not imply a PREMIUM BmApp tier, and PREMIUM never grants
  BmWeb administration rights.

## Tiers and capabilities

All existing and newly granted accounts default to `BASIC`. Existing BmApp
features remain available to both tiers.

| Tier | Additional capabilities |
|---|---|
| `BASIC` | None |
| `PREMIUM` | `COMPREHENSIVE_COMPARATOR_PDF` |

The comprehensive PDF is the first premium feature. Both tiers keep receiving
the Basic comparator PDF from `POST /api/v1/reports/comparator-pdf` (unchanged).
The comprehensive report is served by `POST /api/v1/reports/premium-pdf`, which
enforces `COMPREHENSIVE_COMPARATOR_PDF` server-side (see below).

The backend database is authoritative. Firebase custom claims are not used for
tiers, so an upgrade or downgrade takes effect on the next API request.

## API

`GET /api/v1/me/access` requires a Firebase token and an active BmApp grant. It
returns the current tier and capabilities:

```json
{
  "tier": "PREMIUM",
  "capabilities": ["COMPREHENSIVE_COMPARATOR_PDF"]
}
```

`/me/access` drives client UI only; enforcement happens on the report route.

### Comprehensive report (`POST /api/v1/reports/premium-pdf`)

Body: `{"jobId": "<own study job>", "comparison": <same object posted to comparator-pdf>}`.
Checks run in this order on every request; the first failure wins:

| Step | Status | `code` |
|---|---|---|
| Missing/invalid Firebase token | 401 | (existing `ErrorResponse`) |
| No `granted_users` row | 403 | (existing `ErrorResponse`) |
| Current DB tier lacks `COMPREHENSIVE_COMPARATOR_PDF` (checked before the body is read) | 403 | `premium_required` |
| Malformed body / missing `jobId` | 400 | `invalid_request` |
| Study missing, owned by someone else, or older than 1 h (indistinguishable) | 404 | `study_not_found` |
| Study pending/failed | 409 | `study_not_ready` |
| Comparison inconsistent with the study (`ReportDatasetRejection` codes) | 422 | e.g. `cups_mismatch` |
| No document renderer bound (test-only `UnavailablePremiumReportRenderer`) | 501 | `report_unavailable` |
| Renderer error | 500 | `report_generation_failed` |

Success: `200 application/pdf`, `Content-Disposition: attachment; filename="informe_completo_<epochMillis>.pdf"`.
Errors use `ReportErrorResponse {success:false, code, message, requestId}`; `message` is fixed
Spanish copy and never contains customer values, the job id, the email or exception text. One log
line per request records outcome code, requestId and uid only. The renderer seam is
`PremiumReportRenderer`; `Application.kt` binds `PdfPremiumReportRenderer` (feature `report-document`), so a
valid Premium request now returns the assembled PDF (see `CONSUMPTION_REPORT_FLOW.md`, "Premium report assembly").

BmWeb administrators can assign a tier when creating a grant. Omitting `tier`
defaults to `BASIC`:

```http
POST /api/v1/admin/granted-users
```

```json
{
  "email": "user@example.com",
  "tier": "BASIC"
}
```

Tier changes are deliberately non-destructive:

```http
PATCH /api/v1/admin/granted-users/user%40example.com/tier
```

```json
{
  "tier": "PREMIUM"
}
```

This operation must remain separate from grant deletion, which performs a full
account-data wipe and revokes Firebase sessions.
