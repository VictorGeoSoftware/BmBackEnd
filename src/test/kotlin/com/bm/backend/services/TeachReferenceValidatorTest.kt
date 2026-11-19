package com.bm.backend.services

import com.bm.backend.testing.syntheticPngChunk
import com.bm.backend.testing.syntheticReferencePdf
import com.bm.backend.testing.syntheticReferencePng
import com.bm.backend.testing.syntheticReferencePngOfSize
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TeachReferenceValidatorTest {
    private val validator = TeachReferenceValidator()
    private fun pdf(bytes: ByteArray) = validator.validate(bytes, bytes.size, pdf = true)
    private fun png(bytes: ByteArray) = validator.validate(bytes, bytes.size, pdf = false)

    @Test fun `accepts a classic PDF with line endings and permitted trailing whitespace`() {
        for (eol in listOf("\n", "\r\n", "\r")) pdf(syntheticReferencePdf(eol) + " \t\r\n".encodeToByteArray())
        pdf(syntheticReferencePdf(xrefIndent = " \t"))
    }

    @Test fun `rejects PDF renames, truncation and invalid trailer offsets or tokens`() {
        val valid = syntheticReferencePdf().decodeToString()
        val malformed = listOf(
            "ordinary notes", " $valid", valid.replace("1.4", "2.0"), valid.replace("1.4\n", "1.4 "),
            valid.removeSuffix("%%EOF\n"), valid + "not whitespace",
            valid.replace(Regex("startxref\n\\d+"), "startxref\n0"),
            valid.replace(Regex("startxref\n\\d+"), "startxref\n999999999999"),
            valid.replace(Regex("startxref\n\\d+"), "startxref\n12"),
            valid.replace("\nxref\n", "\nxrefother\n"), valid.replace("startxref\n", "startxref "),
        )
        malformed.forEach { assertFailsWith<InvalidReference> { pdf(it.encodeToByteArray()) } }
        assertFailsWith<InvalidReference> { pdf(syntheticReferencePng()) }
    }

    @Test fun `PNG checks CRC, dimensions, chunk order, and complete byte bounds`() {
        val valid = syntheticReferencePng()
        val sig = valid.copyOfRange(0, 8)
        val header = valid.copyOfRange(8, 33)
        val idat = valid.copyOfRange(33, valid.size - 12)
        val end = valid.takeLast(12).toByteArray()
        val text = syntheticPngChunk("tEXt", "Note\u0000synthetic".encodeToByteArray())
        png(valid)
        val split = idat.copyOfRange(8, idat.size - 4)
        png(sig + header + syntheticPngChunk("IDAT", byteArrayOf()) + syntheticPngChunk("IDAT", split) + end)
        val badHeaders = listOf(0, 4097).map { width ->
            val data = ByteBuffer.wrap(header.copyOfRange(8, 21)).putInt(width).array()
            sig + syntheticPngChunk("IHDR", data) + idat + end
        }
        val wrongCrc = valid.copyOf().apply { this[valid.size - 13] = (this[valid.size - 13].toInt() xor 1).toByte() }
        val badLength = valid.copyOf().apply { ByteBuffer.wrap(this).putInt(33, valid.size) }
        (badHeaders + listOf(
            "ordinary notes".encodeToByteArray(), syntheticReferencePdf(), wrongCrc, badLength,
            valid.copyOf(valid.size - 1), valid + byteArrayOf(0), sig + header + header + idat + end,
            sig + text + header + idat + end, sig + header + idat + text + idat + end,
            sig + header + syntheticPngChunk("IDAT", byteArrayOf()) + end, sig + header + end,
            sig + header + idat + syntheticPngChunk("IEND", byteArrayOf(0)), sig + header + idat + end + end,
        )).forEach { assertFailsWith<InvalidReference> { png(it) } }
        fun counted(chunks: Int) = sig + header + ByteArray(chunks * text.size) { text[it % text.size] } + idat + end
        png(counted(253))
        assertFailsWith<InvalidReference> { png(counted(254)) }
    }

    @Test fun `large opaque blocks and PDF trailing whitespace keep checking cancellation`() {
        val limit = 2 * 1024 * 1024
        for ((bytes, pdf) in listOf(
            syntheticReferencePngOfSize(limit) to false,
            syntheticReferencePdf().copyOf(limit).apply { fill(' '.code.toByte(), syntheticReferencePdf().size) } to true,
        )) {
            var checks = 0
            validator.validate(bytes, bytes.size, pdf) { checks++ }
            assertTrue(checks >= 256)
            assertFailsWith<InterruptedException> {
                validator.validate(bytes, bytes.size, pdf) { if (--checks == 10) throw InterruptedException() }
            }
        }
    }
}
