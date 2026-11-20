package com.bm.backend.testing

import java.nio.ByteBuffer
import java.util.zip.CRC32

/** One empty page, a classic xref table, and real offsets. */
internal fun syntheticReferencePdf(eol: String = "\n", xrefIndent: String = ""): ByteArray {
    val pdf = StringBuilder()
    fun line(value: String) { pdf.append(value).append(eol) }
    line("%PDF-1.4")
    val offsets = listOf(
        "<< /Type /Catalog /Pages 2 0 R >>",
        "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
        "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 72 72] /Resources << >> >>",
    ).mapIndexed { index, value ->
        val offset = pdf.length
        line("${index + 1} 0 obj")
        line(value)
        line("endobj")
        offset
    }
    val xref = pdf.append(xrefIndent).length
    line("xref")
    line("0 4")
    line("0000000000 65535 f${if (eol.length == 1) " " else ""}")
    offsets.forEach { line("${it.toString().padStart(10, '0')} 00000 n${if (eol.length == 1) " " else ""}") }
    line("trailer")
    line("<< /Size 4 /Root 1 0 R >>")
    line("startxref")
    line(xref.toString())
    line("%%EOF")
    return pdf.toString().encodeToByteArray()
}

internal fun syntheticReferencePng(): ByteArray {
    val signature = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
    val header = ByteBuffer.allocate(13).putInt(1).putInt(1).put(8).put(4).array()
    // A two-byte grayscale/alpha pixel, preceded by filter byte 0.
    val pixel = byteArrayOf(0, -1, -1)
    val compressed = java.io.ByteArrayOutputStream().apply {
        java.util.zip.DeflaterOutputStream(this).use { it.write(pixel) }
    }.toByteArray()
    return signature + syntheticPngChunk("IHDR", header) + syntheticPngChunk("IDAT", compressed) + syntheticPngChunk("IEND", byteArrayOf())
}

internal fun syntheticPngChunk(type: String, data: ByteArray): ByteArray {
    val name = type.encodeToByteArray()
    val crc = CRC32().apply { update(name); update(data) }
    return ByteBuffer.allocate(12 + data.size).putInt(data.size).put(name).put(data).putInt(crc.value.toInt()).array()
}

internal fun syntheticReferencePngOfSize(size: Int): ByteArray {
    val png = syntheticReferencePng()
    val text = ByteArray(size - png.size - 12) { 'A'.code.toByte() }
    "Note\u0000".encodeToByteArray().copyInto(text)
    return png.copyOfRange(0, 33) + syntheticPngChunk("tEXt", text) + png.copyOfRange(33, png.size)
}
