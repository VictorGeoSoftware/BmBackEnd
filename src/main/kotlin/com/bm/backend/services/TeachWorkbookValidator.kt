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

internal const val TYPES = "[Content_Types].xml"
internal const val RELS = "_rels/.rels"
internal const val TYPES_NS = "http://schemas.openxmlformats.org/package/2006/content-types"
internal const val RELS_NS = "http://schemas.openxmlformats.org/package/2006/relationships"
internal const val DOC_RELS_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
private const val STANDARD_BOOK = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"
private val EXCEL_4_MACRO_SHEET_TYPES = setOf(
    "application/vnd.ms-excel.macrosheet+xml",
    "application/vnd.ms-excel.intlmacrosheet+xml",
)
private val OFFICE_DOCUMENT = setOf(
    "$DOC_RELS_NS/officeDocument",
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
        // Keep the accepted identification grammar and perform it after all member integrity checks.
        val (types, rels, names) = readPackage(bytes, size, checkpoint) { names, read ->
            Triple(read(TYPES, maxXml), read(RELS, maxXml), names)
        }
        identify(names, exact = false,
            types = { child -> identificationXml(types, TYPES_NS, "Types", checkpoint, child) },
            rels = { child -> identificationXml(rels, RELS_NS, "Relationships", checkpoint, child) }) { _, _ -> }
    }

    /** A selected member can be read once. Do not return any result until the remaining members verify. */
    fun <T> readPackage(
        bytes: ByteArray, size: Int, checkpoint: () -> Unit,
        inspect: (Set<String>, (String, Int) -> ByteArray) -> T,
    ): T {
        val members = preflightWorkbookZip(bytes, size, maxEntries, checkpoint)
        val names = members.keys
        workbookCheck(TYPES in names && RELS in names && names.none(::isVbaPart))
        var inflated = 0
        try {
            open(bytes, size, checkpoint).use { zip ->
                val unread = linkedMapOf<String, ZipArchiveEntry>()
                for (entry in zip.entries.asSequence()) {
                    checkpoint()
                    val expected = members[entry.name] ?: throw InvalidWorkbook()
                    workbookCheck(unread.put(entry.name, entry) == null && entry.method == expected.method && entry.rawFlag == expected.flags &&
                        entry.localHeaderOffset == expected.localOffset.toLong() &&
                        entry.compressedSize == expected.compressedSize.toLong() && entry.size == expected.size && entry.crc == expected.crc)
                }
                workbookCheck(unread.keys == names)
                fun readMember(name: String, retainLimit: Int?): ByteArray? {
                    checkpoint()
                    val entry = unread.remove(name) ?: throw InvalidWorkbook()
                    val expected = members.getValue(name)
                    val retained = if (retainLimit != null) ByteArrayOutputStream() else null
                    val crc = CRC32()
                    var actual = 0L
                    val remaining = {
                        checkpoint()
                        minOf(maxInflated - inflated, if (retainLimit != null) retainLimit - checkNotNull(retained).size() else Int.MAX_VALUE)
                    }
                    consume(zip, entry, expected.dataOffset, remaining) { output, count ->
                        if (count > remaining()) throw WorkbookBudgetExceeded()
                        inflated += count
                        actual += count
                        crc.update(output, 0, count)
                        retained?.write(output, 0, count)
                    }
                    workbookCheck(actual == entry.size && crc.value == entry.crc)
                    return retained?.toByteArray()
                }
                val result = inspect(names) { name, limit -> checkNotNull(readMember(name, limit)) }
                for (name in unread.keys.toList()) readMember(name, null)
                checkpoint()
                return result
            }
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

    fun <T> identify(
        names: Set<String>, exact: Boolean,
        types: ((XMLStreamReader) -> Unit) -> Unit,
        rels: ((XMLStreamReader) -> Unit) -> Unit,
        result: (String, (String) -> String?) -> T,
    ): T {
        val defaults = mutableMapOf<String, String>()
        val overrides = mutableMapOf<String, String>()
        // StAX treats null as a namespace wildcard. Keep this only for the existing receipt profile.
        val attributeNamespace = if (exact) XMLConstants.NULL_NS_URI else null
        fun XMLStreamReader.required(name: String) = getAttributeValue(attributeNamespace, name)
            ?.takeIf(String::isNotBlank) ?: throw InvalidWorkbook()
        fun key(name: String) = if (exact) name else name.lowercase(Locale.ROOT)
        val files = names.filterNot { it.endsWith('/') }.associateBy(::key)
        fun part(value: String): String {
            val decoded = value.removePrefix("/")
            workbookCheck(!decoded.endsWith('/') && safeWorkbookPart(decoded))
            return key(decoded)
        }
        types { item ->
            val type = item.required("ContentType").lowercase(Locale.ROOT)
            workbookCheck(type.isNotBlank() && listOf("macroenabled", "vba", "spreadsheetml.template", "sheet.binary").none { it in type })
            workbookCheck(type.substringBefore(';').trim() !in EXCEL_4_MACRO_SHEET_TYPES)
            when (item.localName) {
                "Override" -> {
                    val name = item.required("PartName")
                    workbookCheck(name.startsWith('/'))
                    val key = part(name)
                    workbookCheck(key in files && overrides.put(key, type) == null)
                }
                "Default" -> {
                    val ext = item.required("Extension").lowercase(Locale.ROOT)
                    workbookCheck(ext.isNotEmpty() && ext.all { it in 'a'..'z' || it in '0'..'9' || it in "_-" })
                    workbookCheck(defaults.put(ext, type) == null)
                }
                else -> throw InvalidWorkbook()
            }
        }
        var main: String? = null
        val ids = hashSetOf<String>()
        rels { item ->
            workbookCheck(item.localName == "Relationship" && ids.add(item.required("Id")))
            val type = item.required("Type")
            val target = item.required("Target")
            val mode = item.getAttributeValue(attributeNamespace, "TargetMode")
            workbookCheck("vba" !in type.lowercase(Locale.ROOT))
            if (type in OFFICE_DOCUMENT) {
                workbookCheck(main == null && (!exact || type == "$DOC_RELS_NS/officeDocument") &&
                    (mode == null || mode == "Internal"))
                main = part(target)
            }
        }
        val mainKey = main ?: throw InvalidWorkbook()
        workbookCheck(mainKey != key(TYPES) && mainKey != RELS && mainKey in files)
        fun contentType(name: String) = overrides[name] ?: defaults[name.substringAfterLast('.', "").lowercase(Locale.ROOT)]
        workbookCheck(contentType(mainKey) == STANDARD_BOOK)
        return result(files.getValue(mainKey), ::contentType)
    }

    private fun identificationXml(bytes: ByteArray, ns: String, root: String, checkpoint: () -> Unit, child: (XMLStreamReader) -> Unit) {
        val factory = XMLInputFactory.newDefaultFactory().apply {
            setProperty(XMLInputFactory.SUPPORT_DTD, false)
            setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
            setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "")
            xmlResolver = javax.xml.stream.XMLResolver { _, _, _, _ -> throw XMLStreamException("External XML disabled") }
        }
        val parser = legacyXmlErrors { factory.createXMLStreamReader(ByteArrayInputStream(bytes)) }
        try {
            var depth = 0
            var rootSeen = false
            while (parser.hasNext()) {
                checkpoint()
                when (legacyXmlErrors { parser.next() }) {
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
        } finally { legacyXmlErrors { parser.close() } }
    }

    private inline fun <T> legacyXmlErrors(block: () -> T): T =
        try { block() } catch (_: XMLStreamException) { throw InvalidWorkbook() }

    private fun isVbaPart(path: String): Boolean = path.substringAfterLast('/').lowercase(Locale.ROOT).let {
        it.startsWith("vbaproject") || it.startsWith("vbadata")
    }
}
