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
