package com.bm.backend.services

import com.bm.backend.models.*
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.*
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
    
    private val doclingCustomerApiUrl =
        System.getenv("DOCLING_CUSTOMER_API_URL")
            ?: System.getenv("DOCLING_API_URL")
            ?: "http://localhost:5000"
    private val doclingPriceTablesApiUrl =
        System.getenv("DOCLING_PRICE_TABLES_API_URL")
            ?: System.getenv("DOCLING_API_URL")
            ?: "http://localhost:5001"
    private val n8nConsumptionWebhookUrl =
        System.getenv("N8N_FETCH_USER_CONSUMPTION_WEBHOOK_URL")
            ?: "http://localhost:5678/webhook/fetch-user-consumption"
    private val n8nFetchTotalPricesWebhookUrl =
        System.getenv("N8N_FETCH_TOTAL_PRICES_WEBHOOK_URL")
            ?: "http://localhost:5678/webhook/fetch-total-prices"

    /**
     * Calls Docling price tables API and normalizes the response to backend price table schema.
     */
    suspend fun extractPriceTablesFromPdf(pdfFile: File): PriceTableResponse {
        return try {
            println("Sending PDF to Docling Price Tables API: ${pdfFile.name}, size: ${pdfFile.length()} bytes")

            val httpResponse = client.submitFormWithBinaryData(
                url = "$doclingPriceTablesApiUrl/extract-price-tables",
                formData = formData {
                    append("file", pdfFile.readBytes(), Headers.build {
                        append(HttpHeaders.ContentType, "application/pdf")
                        append(HttpHeaders.ContentDisposition, "filename=\"${pdfFile.name}\"")
                    })
                }
            )

            if (!httpResponse.status.isSuccess()) {
                val errorBody = try {
                    httpResponse.body<String>()
                } catch (e: Exception) {
                    "Unable to read error response body"
                }
                throw Exception("Docling Price Tables API returned error status ${httpResponse.status.value}: $errorBody")
            }

            val responseText: String = httpResponse.body()
            if (responseText.isBlank()) {
                throw Exception("Docling Price Tables API returned empty response body")
            }

            println("Docling Price Tables API response received, length: ${responseText.length} chars")

            parseOrNormalizePriceTableResponse(responseText, pdfFile.name)
        } catch (e: Exception) {
            throw Exception("Failed to extract price tables from PDF via Docling API: ${e.message}", e)
        }
    }

    /**
     * Parses a batch payload that may contain backend-formatted PriceTableResponse JSON,
     * Docling raw extraction JSON, or arrays mixing both formats.
     */
    fun parseBatchPriceTablesPayload(rawBody: String): List<PriceTableResponse> {
        val trimmedBody = rawBody.trim()
        if (trimmedBody.isBlank()) {
            throw Exception("Request body is empty")
        }

        val rootElement = try {
            json.parseToJsonElement(trimmedBody)
        } catch (e: Exception) {
            throw Exception("Request body is not valid JSON: ${e.message}", e)
        }

        return when (rootElement) {
            is JsonArray -> rootElement.mapIndexed { index, element ->
                parseOrNormalizePriceTableResponse(element.toString(), "batch_file_${index + 1}.pdf")
            }
            is JsonObject -> listOf(
                parseOrNormalizePriceTableResponse(rootElement.toString(), "batch_file_1.pdf")
            )
            else -> throw Exception("Invalid request format. Expected a JSON object or array")
        }
    }

    private fun parseOrNormalizePriceTableResponse(responseText: String, fallbackFileName: String): PriceTableResponse {
        return runCatching {
            json.decodeFromString<PriceTableResponse>(responseText)
        }.getOrElse {
            normalizeDoclingPriceTablesResponse(responseText, fallbackFileName)
        }
    }

    private fun normalizeDoclingPriceTablesResponse(responseText: String, fallbackFileName: String): PriceTableResponse {
        val root = json.parseToJsonElement(responseText).jsonObject
        val success = root["success"]?.jsonPrimitive?.booleanOrNull ?: true
        val results = root["results"]?.jsonArray ?: JsonArray(emptyList())

        val normalizedResults = results.flatMap { resultElement ->
            normalizeResult(resultElement, fallbackFileName)
        }

        if (normalizedResults.isEmpty()) {
            throw Exception("Docling response did not contain extractable price tables")
        }

        return PriceTableResponse(
            success = success,
            results = normalizedResults
        )
    }

    private fun normalizeResult(resultElement: JsonElement, fallbackFileName: String): List<PriceTableResult> {
        val resultObject = resultElement.jsonObject

        runCatching {
            return listOf(json.decodeFromJsonElement(PriceTableResult.serializer(), resultObject))
        }

        val fileName = resultObject["fileName"]?.jsonPrimitive?.contentOrNull ?: fallbackFileName
        val extractedTables = resultObject["extracted_tables"]?.jsonObject ?: return emptyList()

        val companyName = extractedTables["filename"]?.jsonPrimitive?.contentOrNull ?: "Unknown"
        val products = extractedTables["producto"]?.jsonArray ?: return emptyList()

        val hasMultipleProducts = products.size > 1

        return products.mapNotNull { productElement ->
            val productObject = productElement.jsonObject
            val productName = productObject["name"]?.jsonPrimitive?.contentOrNull ?: "Producto"

            val potenciaTarifas = extractTarifas(productObject, "preciosPotencia")
            val energiaTarifas = extractTarifas(productObject, "preciosEnergia")

            val normalizedPotenciaTarifas = if (potenciaTarifas.isNotEmpty()) potenciaTarifas else energiaTarifas
            val normalizedEnergiaTarifas = if (energiaTarifas.isNotEmpty()) energiaTarifas else potenciaTarifas

            if (normalizedPotenciaTarifas.isEmpty() || normalizedEnergiaTarifas.isEmpty()) {
                return@mapNotNull null
            }

            PriceTableResult(
                fileName = if (hasMultipleProducts) "$fileName - $productName" else fileName,
                extracted_tables = ExtractedTables(
                    companyName = companyName,
                    termino_de_potencia = TerminoDePotencia(
                        titulo = "Termino de potencia - $productName",
                        tabla_precio_potencia = TablaPrecioPotencia(
                            titulo = "Tabla precio potencia",
                            tarifas = normalizedPotenciaTarifas
                        )
                    ),
                    termino_de_energia = TerminoDeEnergia(
                        titulo = "Termino de energia - $productName",
                        tabla_precio_clasica_base = TablaPrecioClasicaBase(
                            titulo = "Tabla precio clasica base",
                            tarifas = normalizedEnergiaTarifas
                        ),
                        tabla_precio_clasica_unica = TablaPrecioClasicaUnica(
                            titulo = "Tabla precio clasica unica",
                            tarifas = normalizedEnergiaTarifas
                        )
                    )
                )
            )
        }
    }

    private fun extractTarifas(productObject: JsonObject, tableKey: String): List<TarifaRow> {
        val tarifas = productObject[tableKey]
            ?.jsonObject
            ?.get("tarifa")
            ?.jsonArray
            ?: return emptyList()

        return tarifas.mapNotNull { tarifaElement ->
            val tarifaObject = tarifaElement.jsonObject
            val tarifaName = tarifaObject["name"]?.jsonPrimitive?.contentOrNull
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null

            TarifaRow(
                tarifa = tarifaName,
                potencia_contratada = null,
                P1 = tarifaObject["p1"]?.jsonPrimitive?.doubleOrNull,
                P2 = tarifaObject["p2"]?.jsonPrimitive?.doubleOrNull,
                P3 = tarifaObject["p3"]?.jsonPrimitive?.doubleOrNull,
                P4 = tarifaObject["p4"]?.jsonPrimitive?.doubleOrNull,
                P5 = tarifaObject["p5"]?.jsonPrimitive?.doubleOrNull,
                P6 = tarifaObject["p6"]?.jsonPrimitive?.doubleOrNull
            )
        }
    }
    
    /**
     * Calls Docling API to extract data from PDF
     */
    suspend fun extractDataFromPdf(pdfFile: File): DoclingExtractedData {
        return try {
            println("Sending PDF to Docling API: ${pdfFile.name}, size: ${pdfFile.length()} bytes")
            
            val httpResponse = client.submitFormWithBinaryData(
                url = "$doclingCustomerApiUrl/extract-all",
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
            val httpResponse = client.post(n8nConsumptionWebhookUrl) {
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

    /**
     * Triggers the n8n workflow that fetches current Total prices.
     */
    suspend fun triggerFetchTotalPricesWorkflow(): TriggerWorkflowResponse {
        return try {
            val httpResponse = client.post(n8nFetchTotalPricesWebhookUrl) {
                contentType(ContentType.Application.Json)
                setBody(emptyMap<String, String>())
            }

            val responseText = runCatching {
                httpResponse.body<String>()
            }.getOrDefault("")

            if (!httpResponse.status.isSuccess()) {
                val errorBody = responseText.ifBlank { "Unable to read error response body" }
                throw Exception("N8N Total prices webhook returned error status ${httpResponse.status.value}: $errorBody")
            }

            TriggerWorkflowResponse(
                success = true,
                message = "Total prices workflow triggered successfully",
                details = responseText.takeIf { it.isNotBlank() }
            )
        } catch (e: Exception) {
            throw Exception("Failed to trigger Total prices workflow: ${e.message}", e)
        }
    }
    
    fun close() {
        client.close()
    }
}
