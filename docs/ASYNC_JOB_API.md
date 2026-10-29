# Async Job-Based API for Consumption Report Processing

## Overview
The `/fetch-user-consumption-report` endpoint now uses an asynchronous job-based approach to handle long-running PDF processing without connection timeouts.

## Flow

1. **Client uploads PDF** → Server returns `202 Accepted` with `jobId` immediately
2. **Server processes in background** (90+ seconds)
3. **Client polls for status** every 2-3 seconds using the `jobId`
4. **When complete**, client retrieves the result from the status response

## API Endpoints

### 1. POST `/api/v1/fetch-user-consumption-report`
Upload a bill as a PDF or as a photo (JPEG/PNG) for processing.

**Request:**
- Method: `POST`
- Content-Type: `multipart/form-data`
- Body: one file (field name: `file`), with extension `.pdf`, `.jpg`/`.jpeg` or `.png`

Photos are validated by content (JPEG `FF D8 FF` / PNG `89 50 4E 47` magic bytes), then
wrapped into a one-page PDF inside BmBackend (page = image size, long edge downscaled to
2000 px, no cropping) and sent through the same Docling → n8n pipeline as a PDF. The job
and result contract is identical for PDFs and photos. EXIF rotation is not applied.

**Validation errors (400, no job created):**
- `Only PDF, JPEG or PNG files are accepted` — unsupported or missing extension (e.g. `.heic`, `.gif`, `.txt`)
- `Invalid image file` — `.jpg`/`.jpeg`/`.png` whose bytes are not JPEG/PNG
- `No PDF file provided` — no file part in the request

A photo with no readable bill data is accepted (202) and the job ends `FAILED` with the Docling error.

**Response (202 Accepted):**
```json
{
  "jobId": "550e8400-e29b-41d4-a716-446655440000",
  "status": "accepted",
  "message": "Your request is being processed. Use the jobId to check status."
}
```

### 2. GET `/api/v1/consumption-report-status/{jobId}`
Check the status of a job.

**Request:**
- Method: `GET`
- Path parameter: `jobId`

**Response (200 OK):**

**While Processing:**
```json
{
  "jobId": "550e8400-e29b-41d4-a716-446655440000",
  "status": "PROCESSING",
  "message": "Job is being processed",
  "progress": 50,
  "result": null,
  "error": null
}
```

**When Completed:**
```json
{
  "jobId": "550e8400-e29b-41d4-a716-446655440000",
  "status": "COMPLETED",
  "message": "Job completed successfully",
  "progress": 100,
  "result": {
    "success": true,
    "doclingData": { ... },
    "consumptionData": { ... },
    "filteredPrices": { ... }
  },
  "error": null
}
```

**When Failed:**
```json
{
  "jobId": "550e8400-e29b-41d4-a716-446655440000",
  "status": "FAILED",
  "message": "Job failed",
  "progress": 0,
  "result": null,
  "error": "Error message here"
}
```

## Job Status Values

- `PENDING` - Job created but not yet started
- `PROCESSING` - Job is currently being processed
- `COMPLETED` - Job completed successfully, result available
- `FAILED` - Job failed, error message available

## Android Implementation Guide

### 1. Upload PDF and Get Job ID

```kotlin
suspend fun uploadConsumptionReport(pdfFile: File): String {
    val response = client.submitFormWithBinaryData(
        url = "$baseUrl/fetch-user-consumption-report",
        formData = formData {
            append("file", pdfFile.readBytes(), Headers.build {
                append(HttpHeaders.ContentType, "application/pdf")
                append(HttpHeaders.ContentDisposition, "filename=\"${pdfFile.name}\"")
            })
        }
    )
    
    val jobResponse = response.body<JobResponse>()
    return jobResponse.jobId
}
```

### 2. Poll for Job Status

```kotlin
suspend fun pollJobStatus(jobId: String): Flow<JobStatusResponse> = flow {
    while (true) {
        val response = client.get("$baseUrl/consumption-report-status/$jobId")
        val status = response.body<JobStatusResponse>()
        
        emit(status)
        
        when (status.status) {
            JobStatus.COMPLETED, JobStatus.FAILED -> break
            else -> delay(2000) // Poll every 2 seconds
        }
    }
}
```

### 3. Complete Flow Example

```kotlin
viewModelScope.launch {
    try {
        // Step 1: Upload PDF
        val jobId = uploadConsumptionReport(pdfFile)
        
        // Step 2: Poll for status
        pollJobStatus(jobId).collect { status ->
            when (status.status) {
                JobStatus.PENDING, JobStatus.PROCESSING -> {
                    // Update UI with progress
                    _uiState.value = UiState.Loading(status.progress ?: 0)
                }
                JobStatus.COMPLETED -> {
                    // Success! Use status.result
                    _uiState.value = UiState.Success(status.result!!)
                }
                JobStatus.FAILED -> {
                    // Handle error
                    _uiState.value = UiState.Error(status.error ?: "Unknown error")
                }
            }
        }
    } catch (e: Exception) {
        _uiState.value = UiState.Error(e.message ?: "Upload failed")
    }
}
```

### 4. Data Models

```kotlin
@Serializable
data class JobResponse(
    val jobId: String,
    val status: String,
    val message: String
)

@Serializable
data class JobStatusResponse(
    val jobId: String,
    val status: JobStatus,
    val message: String? = null,
    val progress: Int? = null,
    val result: ConsumptionReportResponse? = null,
    val error: String? = null
)

@Serializable
enum class JobStatus {
    PENDING,
    PROCESSING,
    COMPLETED,
    FAILED
}
```

## Benefits

1. **No Connection Timeouts** - Client gets immediate response
2. **Better UX** - Can show progress to user
3. **Resilient** - Client can retry polling if connection drops
4. **Scalable** - Server can process multiple jobs concurrently
5. **Mobile-Friendly** - Works well with Android's lifecycle

## Notes

- Jobs are automatically cleaned up after 1 hour
- Jobs are stored in-memory (will be lost on server restart)
- Can be upgraded to database storage for persistence if needed
- Poll interval of 2-3 seconds is recommended (not too aggressive)

## Retained study history (not exposed)

When a PDF, image or CUPS job completes, the backend also keeps the typed dated history from
the n8n webhook (`Job.history`, see `docs/N8N_INTEGRATION.md`) together with the CUPS on the
same in-memory job owned by the requesting Firebase uid. It is read only through the
owner-scoped `JobService.getHistory(jobId, ownerUid)` (null for other owners).

- The client JSON of `/consumption-report-status`, `/consumption-report-result` and
  `/consumption-report-refresh` is unchanged: it contains no `history` key.
- Refresh updates proposals/taxes only and keeps the retained history untouched.
- Retention is memory-only: it is lost on restart and jobs expire after 1 hour, like the
  annual result. No database table or archive exists.

## Retained supply snapshot (not exposed)

Alongside the history, a completed job keeps the typed supply snapshot (`Job.snapshot`, see
`docs/N8N_INTEGRATION.md`) only when it belongs to the study CUPS; otherwise an `UNAVAILABLE`
snapshot with a single reason code is kept. It is read only through the owner-scoped
`JobService.getSnapshot(jobId, ownerUid)` (null for other owners).

- Status/result/refresh client JSON is unchanged: it contains no `snapshot` key.
- Refresh keeps the retained snapshot instance untouched.
- Memory-only, like the history: lost on restart, expires with the job after 1 hour.

## History coverage (computed on demand, not exposed)

`HistoryCoverageCalculator.calculate(StudyHistory): HistoryCoverage` (`utils/`) is a pure,
never-throwing calculation over a history obtained through `JobService.getHistory(jobId, ownerUid)`.
It is not stored on `Job` and adds no key to any client JSON. Any unexpected error yields
`UNAVAILABLE` with reason `calculation_error`; an unavailable history yields `UNAVAILABLE` with
the history's reason. The input history is never modified, reordered or deduplicated.

- **Date boundary (D1, user-approved default):** each row covers the half-open range
  `[startDate, endDate)` (reading-to-reading). A shared endpoint is contiguous. Implemented in one
  place (`span`). Rows are never interpreted as months and never split or prorated.
- **Per family (ACTIVE, REACTIVE, MAX_DEMAND):** a row is usable when `startDate <= endDate` and
  at least one P1–P6 value is non-null (`0.0` is a value). All-null rows go to `missingIntervals`;
  nulls in usable rows are only counted (`nullValues`). `startDate > endDate` rows are listed in
  `invertedIntervals` and excluded; `start == end` rows are usable with zero days.
- **Gaps / overlaps:** gaps are uncovered ranges between usable rows; overlaps list both source
  indices and the shared range. `coveredDays` is the union length (never double-counted).
  Overlap meaning is unresolved: `hasUnresolvedOverlaps` must make downstream summaries suppress
  definitive figures over overlapping ranges.
- **Windows:** anchor is the latest `endDate` of any non-inverted row with a usable family (never
  fetch time); 12/24-month windows `[anchor - n months, anchor)`. Rows are `contained` or
  `crossing` (listed, not prorated); `complete` requires contained coverage of the whole window
  with no gaps and no crossing rows.

## Active summary (computed on demand, not exposed)

`ActiveSummaryCalculator.calculate(history, coverage, snapshot, legacy): ActiveSummary` (`utils/`) is a
pure, never-throwing calculation over the owner-scoped retained history, its `HistoryCoverage`, the
`StudySnapshot` and the legacy annual `CleanedConsumptionData`. It is not stored on `Job` and adds no
key to any client JSON. Errors yield `UNAVAILABLE`/`calculation_error`; inputs are never modified.

- **Windows (A3):** anchor = `HistoryCoverage.anchorDate`; `LATEST_12M = [anchor−12m, anchor)`,
  `PREVIOUS_12M = [anchor−24m, anchor−12m)`, D1 half-open, same contained/crossing rule as coverage.
  Status `COMPLETE` (full union, no gaps/crossing, only value-neutral overlaps), `INCOMPLETE`
  (`partialTotal`, covered/window days, gaps, crossing), `OVERLAP_UNRESOLVED`, `UNAVAILABLE`.
- **Overlaps (A1, user decision 2026-10-29):** an overlap where one row's ACTIVE P1–P6 are all
  `0.0` (blank cells do not qualify) is value-neutral: the window can be `COMPLETE` and the overlap is listed in
  `overlapDisclosures` (must be disclosed in the PDF). Any other overlap ⇒ `OVERLAP_UNRESOLVED`, no
  total/shares.
- **Crossing rows (A2):** listed, excluded from sums, never prorated; they make the window `INCOMPLETE`.
- **Periods (A4):** period total = sum of non-null contained cells (null never 0; all-null ⇒ null);
  window total = sum of period totals; shares only when `COMPLETE` and total > 0 (else null, reason
  `zero_total` when 0). Values are unrounded kWh; no unit/decimal correction.
- **No projection (A5):** `partial = true` whenever `LATEST_12M` is not `COMPLETE`
  (incomplete, overlap-blocked or unavailable).
- **High intervals (A6):** top `HIGH_INTERVAL_COUNT` = 3 contained non-zero-length rows of the latest
  `COMPLETE` or `INCOMPLETE` window (never `OVERLAP_UNRESOLVED`), ranked by kWh/day (ties: start date, index); rows in a non-neutral overlap
  excluded. No threshold, no advice.
- **Source comparison (A7):** SIPS snapshot annual and legacy annual, each kept with provenance;
  `difference = history − source`, `% = difference / source × 100` (null if source 0/null),
  `matches` when |difference| < 0.5 kWh. `NOT_COMPARABLE` if `LATEST_12M` is not `COMPLETE`;
  `SOURCE_UNAVAILABLE` when the source is absent. SIPS/legacy refer to their own (undisclosed) period.
- **Change (A8):** `(latest − previous) / previous × 100` only when both windows are `COMPLETE` and
  previous > 0; otherwise null with `latest_incomplete`, `previous_incomplete` or `previous_zero`.

## Reactive summary (computed on demand, not exposed)

`ReactiveSummaryCalculator.calculate(history, coverage): ReactiveSummary` (`utils/`) is a pure,
never-throwing, descriptive calculation of recorded reactive energy (kVArh) over the owner-scoped
retained history and its `HistoryCoverage`. It is not stored on `Job` and adds no key to any client
JSON. Errors yield `UNAVAILABLE`/`calculation_error`; inputs are never modified.

- **Window (R1):** `LATEST_12M = [anchor−12m, anchor)` only (anchor = `HistoryCoverage.anchorDate`), D1
  half-open, same contained/crossing classification as coverage's REACTIVE 12-month window. Rows with all
  six REACTIVE cells null are not REACTIVE rows. Crossing rows are listed, excluded, never prorated.
  Status `COMPLETE`, `INCOMPLETE` (`partialTotal`, gaps, crossing), `OVERLAP_UNRESOLVED`, `UNAVAILABLE`
  (`no_anchor`, `no_reactive_rows`, history/coverage reason or `calculation_error`).
- **Overlaps (R2):** an overlap is value-neutral only when one row of the pair has all six REACTIVE cells
  exactly `0.0` (blank cells never qualify); it is disclosed in `overlapDisclosures`. Any other overlap ⇒
  `OVERLAP_UNRESOLVED`: no total, no ratios; series rows flagged `inUnresolvedOverlap`. Neutrality is judged
  per family (ACTIVE and REACTIVE independently).
- **Zero vs missing (R3):** period total = sum of non-null contained cells (null never 0, all-null ⇒ null);
  `nullCells` counted; `recordedZero = true` (reason `zero_total`) when every non-null contained value is
  `0.0`; shares only when `COMPLETE` and total > 0.
- **Ratio (R4–R6):** descriptive quotient reactive kVArh / active kWh (unrounded), per period and overall,
  with `activeBasis = HISTORY_ACTIVE_LATEST_12M` (history ACTIVE rows of the same window; never SIPS/legacy
  annual values) and the active window status/dates/totals. Given only when both windows are `COMPLETE`;
  otherwise null with `reactive_overlap`, `reactive_incomplete`, `active_unavailable`, `active_overlap` or
  `active_incomplete` (reactive first). Per period also null with `period_missing` or `period_null_cells`
  (null cell in either family); overall null with `period_null_cells` if any period has one. Active total
  `0.0` ⇒ null `active_zero` (never NaN/Infinity); reactive `0.0` with active > 0 ⇒ `0.0`.
- **Series (R7):** contained REACTIVE rows in date order with values as given (nulls preserved); not months.
- **Reuse (R8):** `utils/FamilyWindowAggregator` (family-agnostic D1 aggregation) is used by the reactive
  calculator only; `ActiveSummaryCalculator` is unchanged (migration pending, see TECH_DEBT #18).
- **Exclusions:** no previous-window comparison, projection, power-factor/cos φ calculation, thresholds,
  penalties, liability, compliance verdicts or meter diagnosis. No unit/decimal correction.

## Demand summary (computed on demand, not exposed)

`DemandSummaryCalculator.calculate(history, coverage, snapshot): DemandSummary` (`utils/`) is a pure,
never-throwing, descriptive calculation of recorded maximeter demand (kW) over the owner-scoped retained
history, its `HistoryCoverage` and the retained `StudySnapshot`. It is not stored on `Job` and adds no key
to any client JSON. Errors yield `UNAVAILABLE`/`calculation_error`; inputs are never modified.

- **Window and rows (M1):** `LATEST_12M = [anchor−12m, anchor)` (D1 half-open), same classification as
  coverage's MAX_DEMAND 12-month window. A demand row is any non-inverted row with at least one non-null
  `maxDemand` cell, independent of its active/reactive values (a zero-energy row with demand is kept).
  Only contained rows feed statistics; crossing rows are listed and excluded. Window `COMPLETE`,
  `INCOMPLETE` (`partial = true`, gaps, crossing, `coveredDays`/`windowDays`) or `UNAVAILABLE`
  (`no_contained_rows`). Summary `UNAVAILABLE` reasons: history/coverage reason, `no_anchor`,
  `no_demand_rows`, `calculation_error`.
- **Peak and overlaps (M2):** `peakKw` = maximum non-null contained reading per period, with the source
  row index and dates (tie: earliest start, then lowest index). Overlaps are disclosed in
  `overlapDisclosures` and series `inOverlap`; they never block the peak or drop a row.
- **Zero vs missing (M3):** `0.0` is a recorded reading; null is never 0. A period without readings has
  `peakKw = null`, reason `no_readings`. `nullCells` counted. Negative values are kept as given.
- **Mean (M4):** `meanKw` = arithmetic mean of the non-null contained readings (zeros included, one per row,
  not day-weighted): "media de los máximos registrados por lectura", not average power. Given only when the
  window is `COMPLETE` and no contained demand rows overlap; otherwise null with `window_incomplete` or
  `overlap`. `readings` is always given.
- **Contracted power side by side (M5):** from `StudySnapshot.fields.contractedPowerKw` when the snapshot is
  `AVAILABLE` (`contractedSource = SNAPSHOT_CONTRACTED_POWER_KW`): `contractedKw`,
  `peakMinusContractedKw`, `peakPctOfContracted`. Null with `snapshot_unavailable`, `contracted_missing`,
  `contracted_zero` or `no_readings`. Allowed on a partial window. Descriptive only: no flag, threshold or
  verdict. Snapshot `contractedPowerCheck` and SIPS BIE/APM/access/extension rights are not used.
- **Series (M6):** contained demand rows in date order: values as given (nulls kept), `inOverlap`,
  `activeAllZero`. Not months; no curve; crossing rows excluded.
- **Reuse (M7):** `FamilyWindowAggregator` is used unchanged for classification only; its sums and
  blocking indices are ignored for demand.
- **Exclusions:** no power optimisation, recommended power, excess-power charges, penalties, liability,
  meter diagnosis, projection or unit/decimal correction.

## Premium report dataset (assembled on demand, not exposed)

`ReportDatasetService.assemble(ReportDatasetRequest(jobId, comparison), requesterUid)` (`services/`, not wired
in `Application.kt` and not reachable from any route yet) reads the in-memory job and returns
`ReportDatasetResult.Assembled(ReportDataset)` or `Rejected(ReportDatasetRejection)`.

- Owner-scoped: another owner's job is indistinguishable from a missing one (`study_not_found`). The job must be
  `COMPLETED` with result, history and snapshot retained (`study_not_ready` otherwise); an `UNAVAILABLE`
  history/snapshot is carried, not rejected.
- Bounded by the same memory-only retention: lost on restart, unavailable once the job expires after 1 hour.
- See `CONSUMPTION_REPORT_FLOW.md` for the comparison provenance and the validation reasons.
