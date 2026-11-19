package com.bm.backend.testing

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal const val BOOK_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"
internal const val IDENT_TYPES = "[Content_Types].xml"
internal const val IDENT_RELS = "_rels/.rels"
internal fun bookTypes(type: String = BOOK_TYPE) = """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Override PartName="/xl/workbook.xml" ContentType="$type"/></Types>"""
internal fun bookRels(target: String = "xl/workbook.xml", extra: String = "") = """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="$target"/>$extra</Relationships>"""
internal fun bookParts(types: String = bookTypes(), rels: String = bookRels()) = linkedMapOf(
    IDENT_TYPES to types.toByteArray(),
    IDENT_RELS to rels.toByteArray(),
    "xl/workbook.xml" to "<workbook/>".toByteArray(),
)

internal fun syntheticZip(parts: Map<String, ByteArray> = bookParts(), deflated: Boolean = false): ByteArray {
    val out = ByteArrayOutputStream()
    ZipOutputStream(out).use { zip ->
        for ((name, data) in parts) {
            val packed = if (!deflated) data.size else {
                val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true)
                try {
                    val compressed = ByteArrayOutputStream()
                    DeflaterOutputStream(compressed, deflater).use { it.write(data) }
                    compressed.size()
                } finally { deflater.end() }
            }
            val entry = ZipEntry(name).apply {
                time = 1700000000000L
                method = if (deflated) ZipEntry.DEFLATED else ZipEntry.STORED
                size = data.size.toLong()
                compressedSize = packed.toLong()
                crc = CRC32().also { it.update(data) }.value
            }
            zip.putNextEntry(entry)
            zip.write(data)
            zip.closeEntry()
        }
    }
    return out.toByteArray()
}

internal fun syntheticWorkbookOfSize(size: Int): ByteArray {
    val parts = bookParts().apply { put("xl/synthetic.txt", byteArrayOf()) }
    parts["xl/synthetic.txt"] = ByteArray(size - syntheticZip(parts).size)
    return syntheticZip(parts).also { check(it.size == size) }
}
