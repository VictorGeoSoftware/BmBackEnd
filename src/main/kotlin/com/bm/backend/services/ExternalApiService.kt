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
        // Configure timeouts for long-running processes (up to 10 minutes)
        install(HttpTimeout) {
            requestTimeoutMillis = 600_000 // 10 minutes
            connectTimeoutMillis = 60_000  // 1 minute for connection
            socketTimeoutMillis = 600_000  // 10 minutes for socket
        }
        
        install(ContentNegotiation) {
            json(json)
        }
        
        // Configure CIO engine for long-running requests
        engine {
            requestTimeout = 600_000 // 10 minutes
        }
    }
    
    private val doclingApiUrl = "http://localhost:5000"
    private val n8nWebhookUrl = "http://localhost:5678/webhook/fetch-user-consumption"
    
    /**
     * Calls Docling API to extract data from PDF
     */
    suspend fun extractDataFromPdf(pdfFile: File): DoclingExtractedData {
        return try {
            val httpResponse = client.submitFormWithBinaryData(
                url = "$doclingApiUrl/extract-all",
                formData = formData {
                    append("file", pdfFile.readBytes(), Headers.build {
                        append(HttpHeaders.ContentType, "application/pdf")
                        append(HttpHeaders.ContentDisposition, "filename=\"${pdfFile.name}\"")
                    })
                }
            )
            
            // Get raw response text
            val responseText: String = httpResponse.body()
            
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
            
            // Get raw response text for debugging
            val responseText: String = httpResponse.body()

            // Parse the response
            val response = json.decodeFromString<N8nWebhookResponse>(responseText)
            
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
