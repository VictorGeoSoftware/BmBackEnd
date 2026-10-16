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
