package com.bm.backend.services

import com.bm.backend.testing.*
import kotlinx.coroutines.CancellationException
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TeachWorkbookValidatorTest {
    private fun verify(bytes: ByteArray, validator: TeachWorkbookValidator = TeachWorkbookValidator()) =
        validator.validate(bytes, bytes.size) {}

    @Test fun `accepts ordinary stored and deflated OOXML and does not parse main or unrelated parts as XML`() {
        val parts = bookParts().apply {
            put("xl/workbook.xml", "not parsed in this slice".toByteArray())
            put("xl/worksheets/sheet1.xml", "not parsed either".toByteArray())
        }
        verify(syntheticZip(parts))
        verify(syntheticZip(parts, deflated = true))
    }

    @Test fun `rejects invalid archives disguised types missing or ambiguous workbook and VBA`() {
        val wrongTypes = listOf("application/vnd.ms-excel.sheet.macroEnabled.main+xml",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.template.main+xml", "application/vnd.ms-excel.sheet.binary.macroEnabled.main")
        val candidates = listOf("not a workbook".toByteArray(), syntheticZip(mapOf("sample.txt" to byteArrayOf(1))),
            syntheticZip(bookParts(rels = bookRels("https://example.invalid/book.xml"))),
            syntheticZip(bookParts(rels = bookRels("xl/absent.xml"))),
            syntheticZip(bookParts(rels = bookRels(extra = """<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>"""))),
            syntheticZip(bookParts().apply { put("xl/vbaProject.bin", byteArrayOf(1)) }),
            syntheticZip(bookParts().apply { put("XL/WORKBOOK.XML", byteArrayOf(1)) }),
            syntheticZip(bookParts().apply { put("../sample.txt", byteArrayOf(1)) })) +
            wrongTypes.map { syntheticZip(bookParts(types = bookTypes(it))) }
        candidates.forEach { assertFailsWith<InvalidWorkbook> { verify(it) } }
    }

    @Test fun `rejects Excel 4 macro-sheet and international macro-sheet declarations without inspecting part contents`() {
        fun withDeclaration(xml: String): ByteArray = syntheticZip(bookParts(types = bookTypes().replace("</Types>", "$xml</Types>"))
            .apply { put("xl/metadata/sample.xml", "<synthetic/>".toByteArray()) })
        fun declaration(type: String) = """<Override PartName="/xl/metadata/sample.xml" ContentType="$type"/>"""

        verify(withDeclaration(declaration("application/xml")))
        assertFailsWith<InvalidWorkbook> {
            verify(withDeclaration(declaration("application/vnd.ms-excel.macrosheet+xml")))
        }
        assertFailsWith<InvalidWorkbook> {
            verify(withDeclaration(declaration("application/vnd.ms-excel.intlmacrosheet+xml")))
        }
    }

    @Test fun `rejects truncated archives corrupt CRC and incoherent local metadata`() {
        val valid = syntheticZip()
        assertFailsWith<InvalidWorkbook> { verify(valid.copyOf(valid.size - 22)) }
        assertFailsWith<InvalidWorkbook> { verify(valid + byteArrayOf(0)) }
        val crc = valid.copyOf().apply { this[30 + IDENT_TYPES.length] = 0x20 }
        assertFailsWith<InvalidWorkbook> { verify(crc) }
        val mismatched = valid.copyOf().apply { this[30] = 'X'.code.toByte() }
        assertFailsWith<InvalidWorkbook> { verify(mismatched) }
    }

    @Test fun `rejects data descriptors and damage to an otherwise normally deflated member`() {
        val withDescriptors = ByteArrayOutputStream()
        ZipOutputStream(withDescriptors).use { writer ->
            bookParts().forEach { (name, data) ->
                writer.putNextEntry(ZipEntry(name))
                writer.write(data)
                writer.closeEntry()
            }
        }
        assertFailsWith<InvalidWorkbook> { verify(withDescriptors.toByteArray()) }
        val damaged = syntheticZip(deflated = true).apply {
            val firstPayload = 30 + IDENT_TYPES.length
            this[firstPayload] = (this[firstPayload].toInt() xor 0x01).toByte()
        }
        assertFailsWith<InvalidWorkbook> { verify(damaged) }
    }

    @Test fun `preflight counts real records before opening the reader`() {
        val parts = bookParts().apply { repeat(253) { put("xl/example$it.txt", byteArrayOf(1)) } }
        verify(syntheticZip(parts))
        val oneMore = syntheticZip(parts.apply { put("xl/one-more.txt", byteArrayOf(1)) })
        // A declared count of 256 does not make 257 actual records legal.
        for (index in listOf(oneMore.size - 14, oneMore.size - 12)) oneMore[index] = 0
        var opens = 0
        val instrumented = TeachWorkbookValidator(open = { _, _, _ -> opens++; error("The reader must not open") })
        assertFailsWith<WorkbookBudgetExceeded> { verify(oneMore, instrumented) }
        assertEquals(0, opens)
    }

    @Test fun `counts actual expansion and identification XML exact maximum and one extra`() {
        val parts = bookParts().apply { put("xl/sample.txt", ByteArray(1500)) }
        val bytes = syntheticZip(parts, deflated = true)
        val total = parts.values.sumOf(ByteArray::size)
        val xml = maxOf(parts.getValue(IDENT_RELS).size, parts.getValue(IDENT_TYPES).size)
        verify(bytes, TeachWorkbookValidator(maxInflated = total, maxXml = xml))
        assertFailsWith<WorkbookBudgetExceeded> { verify(bytes, TeachWorkbookValidator(maxInflated = total - 1)) }
        assertFailsWith<WorkbookBudgetExceeded> { verify(bytes, TeachWorkbookValidator(maxXml = xml - 1)) }
    }

    @Test fun `rejects DTD and external entity without accessing a shared resource`() {
        val types = """<!DOCTYPE Types [<!ENTITY note SYSTEM "file:///not-an-input">]>""" +
            bookTypes().replace("</Types>", "&note;</Types>")
        assertFailsWith<InvalidWorkbook> { verify(syntheticZip(bookParts(types = types))) }
    }

    @Test fun `checkpoint interrupts bounded validation and a later validation succeeds`() {
        val bytes = syntheticZip(bookParts().apply { put("xl/sample.txt", ByteArray(64_000)) }, deflated = true)
        val validator = TeachWorkbookValidator()
        var checkpoints = 0
        assertFailsWith<CancellationException> { validator.validate(bytes, bytes.size) {
            if (++checkpoints == 12) throw CancellationException("ordinary test cancellation")
        } }
        verify(bytes, validator)
    }
}
