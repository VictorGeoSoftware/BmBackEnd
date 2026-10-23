package com.bm.backend.routes

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UploadedFileNameSanitizerTest {

    @Test
    fun `keeps a normal filename untouched`() {
        assertEquals("Tarifas ADX 2026.pdf", UploadedFileNameSanitizer.sanitize("Tarifas ADX 2026.pdf"))
    }

    @Test
    fun `is deterministic so re-uploads map to the same natural key`() {
        val name = "ELEIA precios 3.0TD.pdf"
        assertEquals(UploadedFileNameSanitizer.sanitize(name), UploadedFileNameSanitizer.sanitize(name))
    }

    @Test
    fun `strips unix and windows path segments`() {
        assertEquals("table1.pdf", UploadedFileNameSanitizer.sanitize("../../etc/table1.pdf"))
        assertEquals("table1.pdf", UploadedFileNameSanitizer.sanitize("C:\\Users\\me\\table1.pdf"))
    }

    @Test
    fun `removes quotes and control characters and collapses whitespace`() {
        assertEquals("a b.pdf", UploadedFileNameSanitizer.sanitize("  a\"\r\n\t  b.pdf "))
    }

    @Test
    fun `falls back when the name is missing or empty`() {
        assertEquals("uploaded.pdf", UploadedFileNameSanitizer.sanitize(null))
        assertEquals("uploaded.pdf", UploadedFileNameSanitizer.sanitize("   "))
        assertEquals("uploaded.pdf", UploadedFileNameSanitizer.sanitize("dir/"))
        assertEquals("uploaded.pdf", UploadedFileNameSanitizer.sanitize(".pdf"))
    }

    @Test
    fun `truncates long names while keeping the pdf extension`() {
        val sanitized = UploadedFileNameSanitizer.sanitize("x".repeat(400) + ".PDF")
        assertEquals(UploadedFileNameSanitizer.MAX_LENGTH, sanitized.length)
        assertTrue(sanitized.endsWith(".PDF"))
    }

    @Test
    fun `does not add an extension that was not there`() {
        assertEquals("notes.txt", UploadedFileNameSanitizer.sanitize("notes.txt"))
    }
}
