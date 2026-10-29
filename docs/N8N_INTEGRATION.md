# n8n Integration Guide

This document explains how to configure n8n to integrate with the Price Table Backend Service.

## API Endpoints

### Store Price Tables
- **URL**: `http://localhost:8080/api/v1/store-price-tables`
- **Method**: POST
- **Content-Type**: application/json

### Get All Price Tables
- **URL**: `http://localhost:8080/api/v1/price-tables`
- **Method**: GET
- **Query Parameters**:
  - `limit`: Number of records to return (optional)
  - `offset`: Starting point for pagination (optional)
  - `filename`: Filter by filename (optional)
  - `source`: Filter by source (optional)

### Get Specific Price Table
- **URL**: `http://localhost:8080/api/v1/price-tables/{id}`
- **Method**: GET
- **Path Parameters**:
  - `id`: Record ID

## n8n HTTP Request Node Configuration

### Basic Setup
1. Add an **HTTP Request** node to your n8n workflow
2. Set **Method** to `POST`
3. Set **URL** to `http://localhost:8080/api/v1/store-price-tables`
4. Set **Content-Type** to `application/json`

### Headers Configuration
```json
{
  "Content-Type": "application/json"
}
```

### Body Configuration
Use the following JSON structure in the **Body** field:

```json
{
  "filename": "{{ $json.fileName }}",
  "extracted_tables": {
    "termino_potencia": {{ $json.extracted_tables.termino_potencia }},
    "termino_energia_clasica_base": {{ $json.extracted_tables.termino_energia_clasica_base }},
    "termino_energia_clasica_unica": {{ $json.extracted_tables.termino_energia_clasica_unica }}
  },
  "source": "n8n-workflow",
  "timestamp": "{{ new Date().toISOString() }}"
}
```

### Error Handling Configuration
1. In the **HTTP Request** node settings:
   - Enable **Continue On Fail**
   - Set **Retry On Fail** to `3`
   - Set **Wait Between Tries** to `1000` (milliseconds)

2. Add an **IF** node after the HTTP Request:
   - Condition: `{{ $json.success }}` equals `true`
   - True branch: Continue workflow
   - False branch: Handle error (send notification, log, etc.)

### Example Workflow Setup

```json
{
  "nodes": [
    {
      "parameters": {
        "method": "POST",
        "url": "http://localhost:8080/api/v1/store-price-tables",
        "authentication": "genericCredentialType",
        "genericAuthType": "httpHeaderAuth",
        "headers": {
          "Content-Type": "application/json"
        },
        "body": "{\n  \"filename\": \"{{ $json.fileName }}\",\n  \"extracted_tables\": {{ JSON.stringify($json.extracted_tables) }},\n  \"source\": \"n8n-workflow\",\n  \"timestamp\": \"{{ new Date().toISOString() }}\"\n}"
      },
      "name": "Store Price Tables",
      "type": "n8n-nodes-base.httpRequest",
      "typeVersion": 1,
      "position": [450, 300]
    }
  ]
}
```

### Response Handling

The API returns the following response structure:

**Success Response (201 Created)**:
```json
{
  "success": true,
  "message": "Data stored successfully",
  "record_id": 123
}
```

**Error Response (400 Bad Request)**:
```json
{
  "success": false,
  "message": "Validation failed",
  "details": "Filename cannot be blank"
}
```

### Testing the Integration

1. **Start the backend service**:
   ```bash
   ./gradlew run
   ```

2. **Test with curl**:
   ```bash
   curl -X POST http://localhost:8080/api/v1/store-price-tables \
     -H "Content-Type: application/json" \
     -d '{
       "filename": "test.pdf",
       "extracted_tables": {
         "termino_potencia": {"data": "sample"},
         "termino_energia_clasica_base": {"data": "sample"},
         "termino_energia_clasica_unica": {"data": "sample"}
       },
       "source": "n8n-test",
       "timestamp": "2024-01-01T00:00:00Z"
     }'
   ```

3. **Verify data storage**:
   ```bash
   curl http://localhost:8080/api/v1/price-tables
   ```

### Production Considerations

1. **Change base URL** from `localhost:8080` to your production server URL
2. **Add authentication** if implemented (Bearer token or API key)
3. **Use HTTPS** for production environments
4. **Configure rate limiting** in n8n to respect API limits
5. **Set up monitoring** and alerting for failed requests

### Troubleshooting

- **Connection refused**: Ensure the backend service is running
- **400 Bad Request**: Check JSON structure and required fields
- **500 Internal Server Error**: Check backend logs for details
- **Rate limit exceeded**: Implement retry logic with exponential backoff

### Consumption webhook `data.history` (contract v1)

`ExternalApiService.processWithN8nWebhook` decodes the optional `data.history` object
from the `fetch-user-consumption` webhook as raw JSON (`N8nConsumptionData.history`).
`utils/StudyHistoryMapper` turns it into the typed `StudyHistory` model
(`StudyHistoryInterval`, `PeriodValues` with named `p1`..`p6`):

- Values must be JSON numbers or `null`; they are kept as-is as `Double?`
  (no decimal correction, rounding, unit conversion, reordering or aggregation).
  Dates are strict ISO `LocalDate`; intervals stay in source order.
- `AVAILABLE`: `version: 1`, `status: "available"` and an `intervals` array (possibly empty).
- `UNAVAILABLE` with one reason code (never partial data, never values or the CUPS):
  upstream `missing` / `invalid_date` / `invalid_measurement`; `not_provided` (no `history`
  key, older workflow); `unsupported_version` (version other than 1); `malformed`
  (wrong types, string numbers, non-finite values, non-JSON number tokens such as `16.1d`
  accepted by the lenient decoder — see `utils/StrictJsonNumber` — period objects without exactly P1–P6,
  impossible dates, missing groups, `available` with `intervals: null`).
- A history defect never fails the job or changes the annual result. The legacy `=`-prefixed
  annual fields are parsed exactly as before.

### Consumption webhook `data.snapshot` (contract v1)

The optional `data.snapshot` object is decoded as raw JSON (`N8nConsumptionData.snapshot`) and
typed by the total `utils/StudySnapshotMapper` into `StudySnapshot` (`StudySnapshotFields`,
`ContractedPowerCheck`, `PeriodValues`) after the annual result and the history are built:

- `AVAILABLE` requires `version: 1`, `units: {energy: "kWh", power: "kW"}`, `status: "available"`
  and a `fields` object whose `cups` matches the study CUPS (cleaned `data.cups`): equal after
  trim/upper-case, or equal 20-char core when exactly one side carries the 2-char suffix.
  `StudySnapshot.cups` is always the study CUPS.
- Values are kept as published: numbers as `Double` (strict JSON numbers only, powers already
  kW, no conversion/rounding), text unchanged, dates as the source ISO string after strict
  validation (`YYYY-MM-DD` or full ISO date-time). Absent optional keys stay `null`; period maps
  keep only `P1`–`P6` (absent period = `null`). Fetch/processing times are never used as
  source dates. `checks.contractedPowerVsPortal` is stored as `ContractedPowerCheck` (or `null`)
  and never changes the status or values.
- `UNAVAILABLE` with one reason and no fields: upstream `not_captured` / `parse_error` /
  `cups_mismatch` / `invalid_fields`; `not_provided` (no `snapshot` key); `unsupported_version`;
  `cups_mismatch` (backend re-check); `malformed` (wrong types, string or non-JSON numbers,
  explicit `null` values, period keys outside P1–P6, blank `fields.cups`, impossible dates,
  wrong units, `available` with `fields: null`, unknown reason or check value).
- A snapshot defect never fails the job or changes the annual result or history.

### Additional Resources

- API Documentation: Available at `http://localhost:8080` when running
- Health Check: `http://localhost:8080/health`
- Database: SQLite file `price_tables.db` in project root
