package com.bm.backend.services

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipFile
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.DataFormatException
import java.util.zip.Inflater
import javax.xml.XMLConstants
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants.*
import javax.xml.stream.XMLStreamException
import javax.xml.stream.XMLStreamReader

private const val TYPES = "[Content_Types].xml"
private const val RELS = "_rels/.rels"
private const val TYPES_NS = "http://schemas.openxmlformats.org/package/2006/content-types"
private const val RELS_NS = "http://schemas.openxmlformats.org/package/2006/relationships"
private const val STANDARD_BOOK = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"
private val EXCEL_4_MACRO_SHEET_TYPES = setOf(
    "application/vnd.ms-excel.macrosheet+xml",
    "application/vnd.ms-excel.intlmacrosheet+xml",
)
private val OFFICE_DOCUMENT = setOf(
    "http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument",
    "http://purl.oclc.org/ooxml/officeDocument/relationships/officeDocument",
)

internal class TeachWorkbookValidator(
    private val maxEntries: Int = 256,
    private val maxInflated: Int = 32 * 1024 * 1024,
    private val maxXml: Int = 256 * 1024,
    private val open: (ByteArray, Int, () -> Unit) -> ZipFile = { bytes, size, checkpoint ->
        val channel = SeekableInMemoryByteChannel(bytes)
        channel.truncate(size.toLong())
        val reader = object : SeekableByteChannel by channel {
            override fun read(dst: ByteBuffer): Int {
                checkpoint()
                return channel.read(dst)
            }
            override fun position(newPosition: Long): SeekableByteChannel {
                checkpoint()
                channel.position(newPosition)
                return this
            }
        }
        ZipFile.builder().setSeekableByteChannel(reader).setUseUnicodeExtraFields(false).setIgnoreLocalFileHeader(true).get()
    },
) {
    fun validate(bytes: ByteArray, size: Int, checkpoint: () -> Unit) {
        val members = preflightWorkbookZip(bytes, size, maxEntries, checkpoint)
        val names = members.keys
        workbookCheck(TYPES in names && RELS in names && names.none(::isVbaPart))
        var inflated = 0
        val xml = mutableMapOf<String, ByteArray>()
        try {
            open(bytes, size, checkpoint).use { zip ->
                val seen = hashSetOf<String>()
                for (entry in zip.entries.asSequence()) {
                    checkpoint()
                    val expected = members[entry.name] ?: throw InvalidWorkbook()
                    workbookCheck(seen.add(entry.name) && entry.method == expected.method && entry.rawFlag == expected.flags &&
                        entry.localHeaderOffset == expected.localOffset.toLong() &&
                        entry.compressedSize == expected.compressedSize.toLong() && entry.size == expected.size && entry.crc == expected.crc)
                    val retained = if (entry.name == TYPES || entry.name == RELS) ByteArrayOutputStream() else null
                    val crc = CRC32()
                    var actual = 0L
                    val remaining = {
                        checkpoint()
                        minOf(maxInflated - inflated, if (retained != null) maxXml - retained.size() else Int.MAX_VALUE)
                    }
                    consume(zip, entry, expected.dataOffset, remaining) { output, count ->
                        if (count > remaining()) throw WorkbookBudgetExceeded()
                        inflated += count
                        actual += count
                        crc.update(output, 0, count)
                        retained?.write(output, 0, count)
                    }
                    workbookCheck(actual == entry.size && crc.value == entry.crc)
                    if (retained != null) xml[entry.name] = retained.toByteArray()
                }
                workbookCheck(seen == names)
            }
            identify(xml.getValue(TYPES), xml.getValue(RELS), names, checkpoint)
        } catch (_: IOException) { throw InvalidWorkbook() }
        catch (_: DataFormatException) { throw InvalidWorkbook() }
        catch (_: XMLStreamException) { throw InvalidWorkbook() }
    }

    /** Commons provides the bounded raw member stream; JDK Inflater proves exact end/consumption. */
    private fun consume(zip: ZipFile, entry: ZipArchiveEntry, expectedOffset: Int, remaining: () -> Int, accept: (ByteArray, Int) -> Unit) {
        val output = ByteArray(8192)
        (zip.getRawInputStream(entry) ?: throw InvalidWorkbook()).use { input ->
            workbookCheck(entry.dataOffset == expectedOffset.toLong())
            if (entry.method == 0) {
                var packed = 0L
                while (true) {
                    val count = input.read(output, 0, minOf(output.size, remaining() + 1))
                    if (count < 0) break
                    packed += count
                    accept(output, count)
                }
                workbookCheck(packed == entry.compressedSize)
                return
            }
            val inflater = Inflater(true)
            try {
                val compressed = ByteArray(8192)
                while (!inflater.finished()) {
                    val limit = minOf(output.size, remaining() + 1)
                    if (inflater.needsInput()) {
                        val read = input.read(compressed)
                        workbookCheck(read > 0)
                        inflater.setInput(compressed, 0, read)
                    }
                    val count = inflater.inflate(output, 0, limit)
                    workbookCheck(count > 0 || inflater.finished() || (inflater.needsInput() && !inflater.needsDictionary()))
                    if (count > 0) accept(output, count)
                }
                workbookCheck(inflater.bytesRead == entry.compressedSize && input.read() == -1)
            } finally { inflater.end() }
        }
    }

    private fun identify(types: ByteArray, rels: ByteArray, names: Set<String>, checkpoint: () -> Unit) {
        val defaults = mutableMapOf<String, String>()
        val overrides = mutableMapOf<String, String>()
        val files = names.filterNot { it.endsWith('/') }.associateBy { it.lowercase(Locale.ROOT) }
        fun part(value: String): String {
            val decoded = value.removePrefix("/")
            workbookCheck(!decoded.endsWith('/') && safeWorkbookPart(decoded))
            return decoded.lowercase(Locale.ROOT)
        }
        identificationXml(types, TYPES_NS, "Types", checkpoint) { item ->
            val type = item.attr("ContentType").lowercase(Locale.ROOT)
            workbookCheck(type.isNotBlank() && listOf("macroenabled", "vba", "spreadsheetml.template", "sheet.binary").none { it in type })
            workbookCheck(type.substringBefore(';').trim() !in EXCEL_4_MACRO_SHEET_TYPES)
            when (item.localName) {
                "Override" -> {
                    val name = item.attr("PartName")
                    workbookCheck(name.startsWith('/'))
                    val key = part(name)
                    workbookCheck(key in files && overrides.put(key, type) == null)
                }
                "Default" -> {
                    val ext = item.attr("Extension").lowercase(Locale.ROOT)
                    workbookCheck(ext.isNotEmpty() && ext.all { it in 'a'..'z' || it in '0'..'9' || it in "_-" })
                    workbookCheck(defaults.put(ext, type) == null)
                }
                else -> throw InvalidWorkbook()
            }
        }
        var main: String? = null
        val ids = hashSetOf<String>()
        identificationXml(rels, RELS_NS, "Relationships", checkpoint) { item ->
            workbookCheck(item.localName == "Relationship" && ids.add(item.attr("Id")))
            val type = item.attr("Type")
            val target = item.attr("Target")
            val mode = item.getAttributeValue(null, "TargetMode")
            workbookCheck("vba" !in type.lowercase(Locale.ROOT))
            if (type in OFFICE_DOCUMENT) {
                workbookCheck(main == null && (mode == null || mode == "Internal"))
                main = part(target)
            }
        }
        val key = main ?: throw InvalidWorkbook()
        workbookCheck(key != TYPES.lowercase(Locale.ROOT) && key != RELS && key in files)
        workbookCheck((overrides[key] ?: defaults[key.substringAfterLast('.', "")]) == STANDARD_BOOK)
    }

    private fun identificationXml(bytes: ByteArray, ns: String, root: String, checkpoint: () -> Unit, child: (XMLStreamReader) -> Unit) {
        val factory = XMLInputFactory.newDefaultFactory().apply {
            setProperty(XMLInputFactory.SUPPORT_DTD, false)
            setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
            setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "")
            xmlResolver = javax.xml.stream.XMLResolver { _, _, _, _ -> throw XMLStreamException("External XML disabled") }
        }
        val parser = factory.createXMLStreamReader(ByteArrayInputStream(bytes))
        try {
            var depth = 0
            var rootSeen = false
            while (parser.hasNext()) {
                checkpoint()
                when (parser.next()) {
                    START_ELEMENT -> {
                        workbookCheck(++depth <= 2 && parser.namespaceURI == ns)
                        workbookCheck(parser.getAttributeValue(XMLConstants.XML_NS_URI, "base") == null)
                        if (depth == 1) {
                            workbookCheck(!rootSeen && parser.localName == root)
                            rootSeen = true
                        } else child(parser)
                    }
                    END_ELEMENT -> depth--
                    DTD, ENTITY_REFERENCE -> throw InvalidWorkbook()
                    CHARACTERS, CDATA -> workbookCheck(parser.isWhiteSpace)
                }
            }
            workbookCheck(rootSeen && depth == 0)
        } finally { parser.close() }
    }

    private fun XMLStreamReader.attr(name: String) = getAttributeValue(null, name)?.takeIf(String::isNotBlank)
        ?: throw InvalidWorkbook()

    private fun isVbaPart(path: String): Boolean = path.substringAfterLast('/').lowercase(Locale.ROOT).let {
        it.startsWith("vbaproject") || it.startsWith("vbadata")
    }
}
