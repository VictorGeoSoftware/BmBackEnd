package com.bm.backend.routes

import com.bm.backend.testing.PriceTableFixtures
import com.lowagie.text.Document
import com.lowagie.text.Paragraph
import com.lowagie.text.pdf.PdfWriter
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*

internal const val SYNTHETIC_PDF_NAME = "pilot-inert.pdf"

// Metadata in a generated PDF can contain a date and document ID; share the exact inert bytes with the child.
private fun fabricatePdf() = ByteArrayOutputStream().use { out ->
    val doc = Document()
    PdfWriter.getInstance(doc, out)
    doc.open()
    doc.add(Paragraph("Synthetic local PDF path regression"))
    doc.close()
    out.toByteArray()
}

internal fun syntheticPdfBytes(): ByteArray = java.util.Base64.getDecoder().decode(checkNotNull(System.getenv("TEACH_IMPORT_INERT_PDF")))

class TeachProposalImportPdfRegressionTest {
    @Test fun `normal PDF multipart routes still list and dedupe with pilot mounted and stores stay separate`() {
        val pdf = fabricatePdf()
        val calls = AtomicInteger()
        val failure = AtomicReference<Throwable?>()
        val output = PriceTableFixtures.response(PriceTableFixtures.result(SYNTHETIC_PDF_NAME, "Inert supplier"))
        val payload = Json.encodeToString(com.bm.backend.models.PriceTableResponse.serializer(), output).encodeToByteArray()
        val upstream = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        upstream.createContext("/extract-price-tables") { exchange ->
            try {
                calls.incrementAndGet()
                check(exchange.requestURI.toString() == "/extract-price-tables" && exchange.requestMethod == "POST")
                val contentType = exchange.requestHeaders.getFirst("Content-Type")
                check(contentType.startsWith("multipart/form-data;"))
                val boundary = contentType.substringAfter("boundary=").trim('"')
                val body = exchange.requestBody.readNBytes(64 * 1024).toString(Charsets.ISO_8859_1)
                val (header, bytes) = body.split("\r\n\r\n", limit = 2)
                check(header.startsWith("--$boundary\r\n") && header.contains("name=file") &&
                    header.contains("filename=\"$SYNTHETIC_PDF_NAME\"") && header.contains("Content-Type: application/pdf", ignoreCase = true))
                check(bytes == pdf.toString(Charsets.ISO_8859_1) + "\r\n--$boundary--\r\n")
                exchange.responseHeaders.set("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, payload.size.toLong())
                exchange.responseBody.write(payload)
            } catch (problem: Throwable) {
                failure.compareAndSet(null, problem)
                runCatching { exchange.sendResponseHeaders(500, -1) }
            } finally { exchange.close() }
        }
        upstream.start()
        try {
            val classpath = checkNotNull(System.getProperty("bm.teach.import.test.classpath"))
            val process = ProcessBuilder(File(System.getProperty("java.home"), "bin/java").path,
                "-cp", classpath, TeachProposalImportPdfChild::class.java.name).inheritIO().apply {
                environment().clear()
                environment().put("DOCLING_PRICE_TABLES_API_URL", "http://127.0.0.1:${upstream.address.port}")
                environment().put("TEACH_IMPORT_INERT_PDF", java.util.Base64.getEncoder().encodeToString(pdf))
            }.start()
            try {
                // Wait even on a failed wire assertion so the child fixture closes and verifies removal.
                assertTrue(process.waitFor(90, TimeUnit.SECONDS), "Isolated PDF child exceeded its deadline")
                failure.get()?.let { throw AssertionError("Synthetic Docling received an unexpected multipart wire", it) }
                assertEquals(0, process.exitValue())
                assertEquals(1, calls.get())
            } finally {
                if (process.isAlive) {
                    process.destroyForcibly()
                    check(process.waitFor(5, TimeUnit.SECONDS)) { "Isolated PDF child survived teardown" }
                }
            }
        } finally { upstream.stop(0) }
    }
}

internal object TeachProposalImportPdfChild {
    @JvmStatic fun main(args: Array<String>) {
        // The unchanged legacy route logs the synthetic PDF digest at INFO; it is not acceptance evidence.
        (org.slf4j.LoggerFactory.getLogger("Application") as ch.qos.logback.classic.Logger).level = ch.qos.logback.classic.Level.WARN
        TeachProposalImportRoutesTest().isolatedPdfChild()
        // The pre-existing ExternalApiService owns a process-lived CIO client.
        kotlin.system.exitProcess(0)
    }
}
