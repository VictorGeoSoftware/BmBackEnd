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
