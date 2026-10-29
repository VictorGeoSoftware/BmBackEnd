# Consumption Report Flow Implementation

## Overview
This document describes the new consumption report processing flow that integrates PDF extraction, external API processing, and price table filtering.

## Architecture Flow

```
PDF Upload → Docling API → N8N Webhook → Price Table Filtering → Consolidated Response
```

## API Endpoints

### 1. POST `/api/v1/fetch-user-consumption-report`
**New endpoint** that orchestrates the entire consumption report flow.

#### Request
- **Method**: POST
- **Content-Type**: multipart/form-data
- **Body**: PDF file (field name: `file`)

#### Response
```json
{
  "success": true,
  "doclingData": {
    "cups_code": "ES0031104740917002MS",
    "customer_details": {
      "address": "Avda CLARA CAMPOAMOR, 2 41900 CAMAS (SEVILLA)",
      "name": "TOTAL"
    },
    "customer_id": {
      "context_text": "...",
      "original_format": "H91726687",
      "type": "CIF",
      "value": "H91726687"
    }
  },
  "consumptionData": {
    "cups": "ES0031102615113014GF0F",
    "tarifa": "2.0TD",
    "tarifaValue": "2.0TD",
    "annualConsumption": 1197.0,
    "annualConsumptionP1": 383.0,
    "annualConsumptionP2": 306.0,
    "annualConsumptionP3": 508.0,
    "annualConsumptionP4": 0.0,
    "annualConsumptionP5": 0.0,
    "annualConsumptionP6": 0.0,
    "subscribedPowerP1": 9.86,
    "subscribedPowerP2": 9.86,
    "subscribedPowerP3": 0.0,
    "subscribedPowerP4": 0.0,
    "subscribedPowerP5": 0.0,
    "subscribedPowerP6": 0.0,
    "feeType": "2.0TD",
    "fileName": "",
    "processedAt": "2025-10-24T01:15:02.681-04:00"
  },
  "filteredPrices": {
    "success": true,
    "results": [...],
    "iva": 21,
    "impuestoElectrico": 5.11
  }
}
```

### 2. GET `/api/v1/price-table-results?tarifaType=2.0TD`
**Updated endpoint** that now accepts an optional query parameter to filter results.

#### Request
- **Method**: GET
- **Query Parameters**:
  - `tarifaType` (optional): Filter price tables by tarifa type (e.g., "2.0TD")

#### Response
Same as before, but filtered if `tarifaType` is provided.

## Processing Steps

### Step 1: PDF Upload & Validation
- Accepts multipart/form-data with PDF file
- Validates file extension (.pdf)
- Creates temporary file for processing

### Step 2: Docling API Extraction
- **Endpoint**: `http://localhost:5000/extract-all`
- **Method**: POST (multipart/form-data)
- Extracts CUPS code, customer details, and customer ID from PDF
- **Error Handling**: Throws exception with "Failed at Docling API extraction step" prefix

### Step 3: N8N Webhook Processing
- **Endpoint**: `http://localhost:5678/webhook/fetch-user-consumption`
- **Method**: POST (JSON)
- Sends extracted Docling data
- Receives consumption data with tarifa information
- **Data Cleaning**: Removes non-alphanumeric characters (except dots) from all fields
- **Error Handling**: Throws exception with "Failed at N8N webhook processing step" prefix

### Step 4: Price Table Filtering
- Calls internal service to get price tables
- Filters by `tarifa` field from N8N response
- Case-insensitive matching on `TarifaRow.tarifa`
- **Error Handling**: Throws exception with "Failed at price table filtering step" prefix

### Step 5: Response Consolidation
- Combines all three data sources into single response
- Returns `ConsumptionReportResponse` with all collected data

## New Models

### ConsumptionReportModels.kt
- `DoclingApiResponse` - Response from Docling API
- `DoclingExtractedData` - Extracted PDF data
- `CustomerDetails` - Customer information
- `CustomerId` - Customer identification
- `N8nWebhookResponse` - Response from N8N webhook
- `N8nConsumptionData` - Raw consumption data from N8N
- `ConsumptionReportResponse` - Final consolidated response
- `CleanedConsumptionData` - Cleaned and parsed consumption data

### Helper Functions
- `String.cleanAndConvert()` - Removes non-alphanumeric characters
- `String.cleanAndConvertToDouble()` - Cleans and converts to Double
- `N8nConsumptionData.toCleanedData()` - Converts raw N8N data to cleaned format

## Services

### ExternalApiService
New service for handling external API calls:
- `extractDataFromPdf(pdfFile: File)` - Calls Docling API
- `processWithN8nWebhook(doclingData)` - Calls N8N webhook
- Uses Ktor HTTP Client (CIO engine)

### UserConsumptionService
Updated with new method:
- `processConsumptionReportFromPdf(pdfFile: File)` - Orchestrates entire flow

### PriceTableService
Updated method:
- `getAllPriceTableResults(tarifaType: String?)` - Now accepts optional filter

## Dependencies Added

```kotlin
// HTTP Client for external API calls
implementation("io.ktor:ktor-client-core:2.3.12")
implementation("io.ktor:ktor-client-cio:2.3.12")
implementation("io.ktor:ktor-client-content-negotiation:2.3.12")
```

## Error Handling

All errors include specific step information:
- "Failed at Docling API extraction step: {error}"
- "Failed at N8N webhook processing step: {error}"
- "Failed at price table filtering step: {error}"

This allows easy debugging of which external service failed.

## Testing

### Manual Testing with cURL

```bash
# Upload PDF and process
curl -X POST http://localhost:8080/api/v1/fetch-user-consumption-report \
  -F "file=@/path/to/consumption.pdf"

# Get filtered price tables
curl "http://localhost:8080/api/v1/price-table-results?tarifaType=2.0TD"
```

## Notes

- Temporary PDF files are automatically cleaned up after processing
- All external API calls are asynchronous (suspend functions)
- Data cleaning removes `=` prefix and other non-alphanumeric characters from N8N response
- Tarifa filtering is case-insensitive
- The old GET `/fetch-user-consumption-report` endpoint was renamed to `/get-user-consumption-report` to avoid conflicts

## Premium report commercial dataset (internal)

The Basic economic comparison is computed in the clients (BmApp/PowerAppWeb): they round the raw backend
proposals (2 dp half-up), add the broker's per-offer fixed amount, compute the current-conditions column from
broker-typed prices and send the result as `ComparatorReportPdfRequest` to `POST /api/v1/reports/comparator-pdf`.
That route and its PDF are unchanged.

The Premium dataset (`ReportDatasetService`, `utils/ReportDatasetValidator.kt`) reuses that request **verbatim
and by identity**; it never recalculates, re-rounds, re-taxes or reorders an amount. It only validates it against
the owner's completed study, first failure wins, with value-free reason codes:

1. `holder_missing` / `address_missing`: trimmed `supplyHolder`/`supplyAddress` blank or `"--"`.
2. `cups_mismatch` (shared `utils/CupsMatch.kt` rule), `tariff_mismatch` (`tariffName` vs study `feeType`,
   trimmed), `consumption_mismatch` (power rows `P1..P6`, unique, equal to subscribed power within 1e-9; energy
   rows equal to the truncated annual kWh per period).
3. `no_offers`, `duplicate_offer` (blank or repeated title), `unknown_offer` (title not in the study's current
   proposals), `stale_offer` (power cost, energy cost or electricity tax more than 0.005 from the raw backend
   value; IVA/total include the broker's fixed amount and are not cross-checked).
4. `current_conditions_missing` (any current-conditions amount blank), `invalid_amount` (computed amounts not
   `^-?\d+\.\d{2}$`; `extraServices` — the broker's fixed amount, offer and current conditions — not in the app
   input format `^\d*([.,]\d{0,2})?$` with at least one digit, e.g. `"12"`, `"12,5"`, `"12."` are accepted and kept
   verbatim (user decision F1, 2026-10-29); malformed difference, non-finite list values, tax labels not
   `^\d+\.\d{2} %$`), `tax_mismatch` (IVA/electricity-tax labels more than 0.005 from the study's percentages).

Not available from any source and therefore absent from the dataset: current supplier name, holder NIF,
contact data, separate postal fields, penalties.

## Premium report assembly

`Application.kt` binds `PdfPremiumReportRenderer(Clock.systemUTC())`, so `POST /api/v1/reports/premium-pdf`
returns `200 application/pdf` for a valid Premium request (user decisions 2026-10-29):

- Order (Q1 a): cover → active → reactive → demand → comparison (landscape, 7 offers/page) → company. No contents
  page (Q2 a).
- Footer (Q3 a, `PremiumPageFooter`, copy in `PremiumDocumentTexts`): `Página {n} de {total}`, 9 pt grey, centred
  in the bottom margin of every page except the cover; the cover counts as page 1. `{total}` is a template filled
  when the document closes, so numbers always match the real output.
- Optional sections (Q4 b): reactive/demand are omitted **only** when the history contains no readings of that
  family (`ReactiveSummaryCalculator.NO_REACTIVE_ROWS` / `DemandSummaryCalculator.NO_DEMAND_ROWS`). Any other
  unavailable reason (e.g. `calculation_error`) or readings only outside the window keeps the section with its
  approved disclosure. Active, comparison, cover and company are always included.
- In memory only (no temp files, no storage, no n8n/portal refetch); renderer and footer are created per request.
  Any section/close exception propagates ⇒ `500 report_generation_failed` with no bytes. Metadata stays neutral.
- Tests: `PremiumReportDocumentTest`, `PremiumReportRoutesTest` (real renderer A/G/H); previews
  `PremiumReportDocumentPreviewTest` → `build/premium-report-preview/document-*.pdf`.
- Tariff periods (spec report-tariff-applicability): `utils/TariffPeriodResolver` is the only place tariff periods
  are defined (2.0TD → energy/reactive P1–P3, power/maximeter P1–P2; 3.0TD → P1–P6). Candidates are the available
  snapshot's `tariff`, then the comparison `tariffName`; texts that are not an access-tariff code (blank, `fixed`)
  carry no evidence. Two different codes ⇒ fallback + T2; an unknown/unsupported code or none ⇒ fallback + T1; a
  supported tariff with non-zero history/snapshot values outside its periods ⇒ fallback + T3. Fallback periods are
  those with any non-null source value (none ⇒ P1–P6). The cover shows T1–T3 once; sections/calculators receive the
  periods (default P1–P6, so 3.0TD output is unchanged). Tests `*Tariff*`, previews `tariff-*.pdf`.

The pieces below are the accepted section renderers that the assembly uses unchanged.

Foundation (`services/premiumreport/`, one type per file):

- `PremiumReportSection` — `fun write(document, writer, dataset)`. A section sets its own page size, then calls
  `document.newPage()` (a no-op on an empty page), so portrait and landscape sections can be mixed.
- `PremiumReportPdf.render(sections, dataset, pageEvent = null)` — opens an A4 portrait document, writes the sections in order and
  closes it. Metadata: title `ESTUDIO COMPLETO DE SUMINISTRO ELÉCTRICO`, author/creator `BM`; never the CUPS,
  holder or address.
- `PremiumReportTheme` — page sizes/margins, colours (Basic highlight `249,196,17`, dark text, grid grey) and
  fonts. Fonts are Liberation Sans 2.1.5 Regular/Bold bundled in `src/main/resources/fonts/` (SIL OFL 1.1,
  `fonts/LICENSE-LiberationSans.txt`) and embedded as Identity-H subsets; a missing font resource throws.
- `PremiumReportBlocks` (yellow section bar, wrapping label/value table, cell), `PremiumReportFormat` (Spanish
  numbers up to 3 decimals, `dd/MM/yyyy` dates, Europe/Madrid issue date, `es-ES` uppercase),
  `PremiumReportTexts` (approved cover copy).

Cover (`PremiumCoverSection(clock)`): `images/logo_briel.png` centred (missing logo → `IllegalStateException`),
title, subtitle `Análisis de consumo y oferta económica`, `Fecha del informe:` and a `DATOS DEL SUMINISTRO` table:

| Row | Source | When shown |
|-----|--------|-----------|
| Titular / Dirección del suministro | broker-entered `comparison.supplyHolder` / `supplyAddress`, uppercased | always |
| CUPS | study CUPS (`dataset.cups`) | always |
| Tarifa de acceso | `comparison.tariffName` (validated equal to the study `feeType`) | always |
| Distribuidora | snapshot `distributorName` | if present and not blank |
| Ubicación | `<municipality> (<province>) — CP <postalCode>`, absent parts dropped | if any part present |
| Potencia contratada | `P1: 15,1 kW · …`, reported periods only | if any period reported |
| Fecha de la última lectura | snapshot `lastReadingDate` as `dd/MM/yyyy` | if present |

If the snapshot is unavailable, the snapshot rows are omitted and one sentence is shown: `No se dispone de datos
adicionales del suministro.` (no reason code). Distributor code, BIE/APM/access/extension rights, other dates,
annual consumption and the contracted-power check are never shown. Values wrap and are never truncated.

Previews: `PremiumCoverPreviewTest` writes synthetic `build/premium-report-preview/cover-*.pdf`; convert with
`pdftoppm -png -r 110` for manual review.

### Active-energy page

`PremiumActiveSection` (portrait, section bar `ENERGÍA ACTIVA`) computes `HistoryCoverageCalculator` and
`ActiveSummaryCalculator` (history, coverage, snapshot, `report.consumptionData`) from the dataset; it adds no
calculation, projection, proration or advice. Copy lives in `PremiumActiveTexts` (approved 2026-10-29). Blocks:

1. `Histórico disponible: dd/MM/yyyy – dd/MM/yyyy` (inclusive, `historyEnd − 1`) plus the partial sentence only when the
   latest window is INCOMPLETE and the history starts after the window start (never for overlap-blocked or
   unavailable windows, which have their own notes).
2. Window table (latest, previous): `COMPLETE` → totals and shares; `INCOMPLETE` → period totals and
   `Parcial: <partialTotal>` with covered/window days, no shares; `OVERLAP_UNRESOLVED` → `Sin cifra firme…`;
   `UNAVAILABLE` → `Sin datos`. Null values show `—`, never 0. YoY only when `changePct` exists, otherwise the
   reason. One note per disclosed overlap (zero-row overlaps: "no altera el total"; blocking: "sin resolver").
3. P1–P6 share chart only for a COMPLETE latest window with shares; otherwise a sentence says why.
4. Readings chart: one bar per dated source row (not per month) on a true date axis; height = reported kWh / days,
   width = days. Null-only rows are hatched gaps, zero rows a marker on the axis, rows in an ACTIVE coverage overlap
   are outlined. At most the 36 most recent rows; the cut is disclosed. Rows with zero or negative span (start ≥ end) are
   excluded from the chart and from its row count, but still listed in the readings table.
5. High-consumption intervals with the source window and status always named (fallback to `PREVIOUS_12M` shown).
6. SIPS (`SIPS (distribuidora)`) and Basic-study (`Consumo anual del estudio básico`) comparisons with source,
   history and differences; the source is never replaced. The note `El histórico cubre <window>; la fuente no indica
   su periodo de referencia.` appears only when the latest window is COMPLETE. `NOT_COMPARABLE` shows source values
   only with a reason from the latest window: OVERLAP_UNRESOLVED → `…tienen lecturas solapadas sin resolver.`;
   no active history at all → `…no hay histórico de energía activa.`; INCOMPLETE, or UNAVAILABLE only because the
   rows cross the window boundary → `…la ventana de 12 meses no está completa`. `SOURCE_UNAVAILABLE` → `Fuente no disponible`.
7. Every history row in source order (inclusive dates, `—` for nulls, `*` for rows crossing the latest window);
   the table splits across pages with a repeated header.

Charts: `ActiveChartModel` (pure geometry in PDF points: `ChartBar`, `ChartTick`, `ChartBarStyle`; 1/2/5×10ⁿ axis
ceiling; deterministic) drawn by `ActiveChartPainter` with OpenPDF vector primitives into a `PdfTemplate` image (no
chart library; labels are real embedded-font text). P1–P6 colours: `PremiumReportTheme.PERIOD_COLORS`, a yellow →
dark-grey ramp; every bar is also labelled. Previews: `PremiumActivePreviewTest` writes
`build/premium-report-preview/active-*.pdf`. Assembled by `PdfPremiumReportRenderer` (see "Premium report assembly").

### Reactive-energy page

`PremiumReactiveSection` (portrait, section bar `ENERGÍA REACTIVA`) computes `HistoryCoverageCalculator` and
`ReactiveSummaryCalculator` from the dataset; it adds no calculation, projection, proration, comparison or advice.
Every sentence is a row of the approved truth table in `docs/specs/report-reactive-page.md` (workspace) and lives in
`PremiumReactiveTexts` (approved 2026-10-29; shared meanings reference `PremiumActiveTexts`). No penalty, power-factor,
threshold, verdict or exclusion statement appears (X1 omitted). Blocks:

1. `Ventana analizada: Últimos 12 meses (<range>)`; when the summary is UNAVAILABLE the page stops after
   `Ventana analizada: Sin datos` plus "no reactive readings" (`no_reactive_rows`) or the generic unavailable sentence.
2. Latest-window table in kVArh (status, covered/window days, P1–P6, total; shares only when COMPLETE with total > 0;
   `Parcial: …` when INCOMPLETE; `Sin cifra firme…`/`Sin datos` spans). Notes: nothing contained in the window,
   partial coverage, crossing readings, recorded zero, null cells, one per overlap disclosure (zero/blocking).
3. P1–P6 share chart (`ReactiveChartModel.distribution`) or the reason it is unavailable.
4. kVArh/kWh descriptive quotient: the basis sentence (active window range) always; the table (2 decimals,
   `PremiumReportFormat.decimal2`, per-cell reason texts) only when `ratioReason` is null, otherwise its reason.
5. Readings chart (`ReactiveChartModel.readings`: one bar per `summary.series` row, kVArh/día on a date axis,
   36-row cap disclosed) and the readings table of the same latest-12-month rows (`†` = unresolved overlap).

`ReactiveChartModel` returns the shared `ActiveChartModel`, so `ActiveChartPainter` draws it unchanged (the model's
tick/month helpers are `internal`). Previews: `PremiumReactivePreviewTest` writes
`build/premium-report-preview/reactive-*.pdf`. Assembled by `PdfPremiumReportRenderer` (see "Premium report assembly").

### Demanded-power page

`PremiumDemandSection` (portrait, section bar `POTENCIA DEMANDADA`) computes `HistoryCoverageCalculator` and
`DemandSummaryCalculator` (with the dataset snapshot) and adds no calculation beyond the presentation-only highest
P1–P6 value per reading in the readings chart. Values are kW as published (`PremiumReportFormat.number`, differences
`signedNumber`, percentages `percent`). Every sentence is a row of the approved truth table in
`docs/specs/report-demand-page.md` (workspace) and lives in `PremiumDemandTexts` (approved 2026-10-29). The
contracted-power comparison is plain arithmetic: no exceed flag, verdict, colour coding, threshold or advice. Blocks:

1. `Ventana analizada: Últimos 12 meses (<range>)`; when the summary is UNAVAILABLE the page stops after
   `Ventana analizada: Sin datos` plus "no demand readings" (`no_demand_rows`) or the generic unavailable sentence.
2. Per-period table (readings, highest recorded value with the dates of its reading, mean of per-reading maxima only
   when the calculator gives it, else the reason) with its definition caption; status notes (nothing contained in the
   window — `no_contained_rows` replaces the table —, partial window, crossing readings, recorded zero, null cells,
   periods without readings, one per overlap disclosure, zero-active readings).
3. Highest recorded value vs contracted power (`DemandChartModel.comparison`: bars plus a neutral vertical
   `ChartMarker` line per period) and its table; replaced by a reason when the snapshot is unavailable or the window
   has no contained reading; partial-window note when INCOMPLETE.
4. Readings chart (`DemandChartModel.readings`: one bar per `summary.series` row, height = its highest P1–P6 value,
   width = days, 36-row cap disclosed) and the table of the same rows (`†` overlap, `°` zero active energy).

`ActiveChartModel.markers` (default empty) and the painter's marker drawing are additive: the active/reactive charts
carry no markers and render unchanged. Previews: `PremiumDemandPreviewTest` writes
`build/premium-report-preview/demand-*.pdf`. Assembled by `PdfPremiumReportRenderer` (see "Premium report assembly").

### Economic comparison page

`PremiumComparisonSection` (landscape, Premium fonts/margins) shows the same comparison as the Basic comparator PDF.
Source: `dataset.comparison` only — the client-built `ComparatorReportPdfRequest` already checked by
`ReportDatasetValidator`; `report.proposals` is never read. `ComparisonTableModel` reproduces Basic's cell strings with
Basic's own rules (copied, not shared, so the Basic service is untouched): 7 offers per page in request order, every
page repeating the section bar (`request.title`), header row and current-conditions column; prices `%.6f` and powers
`%.3f` (Locale.US); client amount strings printed verbatim with a `" €"` suffix; tax labels with the client's
reference; missing price indexes print `0.000000` as Basic; savings `<difference> €` / `<n>%` with Basic's green/red.
Labels come from `ComparatorReportPdfTexts`; no supply block (the cover shows holder/address/CUPS), no supplier name,
ranking or advice. `PremiumReportFormat` is never applied to amounts.

Parity rule: `PremiumComparisonSectionTest` extracts each page of the Basic PDF and of the Premium section for the same
request and requires the same ordered characters (whitespace ignored) once Basic's supply block is removed.
Previews: `PremiumComparisonPreviewTest` writes `build/premium-report-preview/comparison-*.pdf` and the Basic PDF of
each request as `comparison-*-basic.pdf`. Assembled by `PdfPremiumReportRenderer` (see "Premium report assembly").

### Company page

`PremiumCompanySection` (portrait closing page, normally one page; long text may flow, never clipped) renders, in
order: the BM logo (`/images/logo_briel.png`, same resource as the cover; missing → `IllegalStateException`), the
section bar `INFORMACIÓN SOBRE PROTECCIÓN DE DATOS`, the line `BRIEL MARNYSOS CONSULTORÍA ENERGÉTICA S.L.U.` and the
data-protection text `ComparatorReportPdfTexts.DATA_PROTECTION_DISCLAIMER` (9 pt, justified). Copy lives in
`PremiumCompanyTexts`; the legal text is **referenced**, never copied, and must stay verbatim (user decisions
2026-10-29: no company/services presentation, no website). Nothing else is printed, and the page ignores the dataset
(no customer value). `PremiumCompanySectionTest` checks the exact ordered text, the verbatim constant, identical output
across datasets and forbidden vocabulary. Previews: `PremiumCompanyPreviewTest` writes
`build/premium-report-preview/company-*.pdf`. Assembled by `PdfPremiumReportRenderer` (see "Premium report assembly").
