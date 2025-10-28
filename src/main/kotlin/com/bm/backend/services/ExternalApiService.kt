package com.bm.backend.services

import com.bm.backend.models.DoclingApiResponse
import com.bm.backend.models.DoclingExtractedData
import com.bm.backend.models.N8nWebhookResponse
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json
import java.io.File

class ExternalApiService {
    
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        prettyPrint = true
    }
    
    private val client = HttpClient(CIO) {
        // Configure reasonable timeouts for external API calls
        install(HttpTimeout) {
            requestTimeoutMillis = 600_000 // 2 minutes (sufficient for Docling + N8N)
            connectTimeoutMillis = 60_000  // 30 seconds for connection
            socketTimeoutMillis = 600_000  // 2 minutes for socket
        }
        
        install(ContentNegotiation) {
            json(json)
        }
        
        // Configure CIO engine
        engine {
            requestTimeout = 120_000 // 2 minutes
            endpoint {
                connectTimeout = 30_000 // 30 seconds
                socketTimeout = 120_000 // 2 minutes
            }
        }
    }
    
    private val doclingApiUrl = "http://localhost:5000"
    private val n8nWebhookUrl = "http://localhost:5678/webhook/fetch-user-consumption"
    
    /**
     * Calls Docling API to extract data from PDF
     */
    suspend fun extractDataFromPdf(pdfFile: File): DoclingExtractedData {
        return try {
            println("Sending PDF to Docling API: ${pdfFile.name}, size: ${pdfFile.length()} bytes")
            
            val httpResponse = client.submitFormWithBinaryData(
                url = "$doclingApiUrl/extract-all",
                formData = formData {
                    append("file", pdfFile.readBytes(), Headers.build {
                        append(HttpHeaders.ContentType, "application/pdf")
                        append(HttpHeaders.ContentDisposition, "filename=\"${pdfFile.name}\"")
                    })
                }
            )
            
            // Check HTTP status code
            if (!httpResponse.status.isSuccess()) {
                val errorBody = try {
                    httpResponse.body<String>()
                } catch (e: Exception) {
                    "Unable to read error response body"
                }
                throw Exception("Docling API returned error status ${httpResponse.status.value}: $errorBody")
            }
            
            // Get raw response text
            val responseText: String = httpResponse.body()
            
            // Validate response is not empty
            if (responseText.isBlank()) {
                throw Exception("Docling API returned empty response body")
            }
            
            println("Docling API response received, length: ${responseText.length} chars")
            
            // Parse based on response format (single object vs array)
            val responseList: List<DoclingApiResponse> = if (responseText.trimStart().startsWith('[')) {
                json.decodeFromString<List<DoclingApiResponse>>(responseText)
            } else {
                // Single object response - wrap in list
                val singleResponse = json.decodeFromString<DoclingApiResponse>(responseText)
                listOf(singleResponse)
            }
            
            if (responseList.isEmpty()) {
                throw Exception("Docling API returned empty response")
            }
            
            val firstResult = responseList.first()
            if (!firstResult.success) {
                throw Exception("Docling API extraction failed for file: ${firstResult.fileName}")
            }
            
            println("Docling API extraction successful")
            firstResult.extractedData
        } catch (e: Exception) {
            throw Exception("Failed to extract data from PDF via Docling API: ${e.message}", e)
        }
    }
    
    /**
     * Calls N8N webhook to process extracted data
     */
    suspend fun processWithN8nWebhook(doclingData: DoclingExtractedData): N8nWebhookResponse {
        return try {
            val httpResponse = client.post(n8nWebhookUrl) {
                contentType(ContentType.Application.Json)
                setBody(doclingData)
            }
            
            // Check HTTP status code
            if (!httpResponse.status.isSuccess()) {
                val errorBody = try {
                    httpResponse.body<String>()
                } catch (e: Exception) {
                    "Unable to read error response body"
                }
                throw Exception("N8N webhook returned error status ${httpResponse.status.value}: $errorBody")
            }
            
            // Get raw response text for debugging
            val responseText: String = httpResponse.body()
            
            // Validate response is not empty
            if (responseText.isBlank()) {
                throw Exception("N8N webhook returned empty response body. This usually indicates the N8N workflow failed or timed out.")
            }
            
            // Log response for debugging (first 500 chars)
            println("N8N Response (first 500 chars): ${responseText.take(500)}")

            // Parse the response
            val response = try {
                json.decodeFromString<N8nWebhookResponse>(responseText)
            } catch (e: Exception) {
                throw Exception("Failed to parse N8N response as JSON. Response: ${responseText.take(200)}. Error: ${e.message}", e)
            }
            
            if (!response.success) {
                throw Exception("N8N webhook processing failed")
            }
            
            response
        } catch (e: Exception) {
            throw Exception("Failed to process data via N8N webhook: ${e.message}", e)
        }
    }
    
    fun close() {
        client.close()
    }
}
