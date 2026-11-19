package com.bm.backend.services

import com.bm.backend.models.TeachClarificationInspection
import com.bm.backend.models.TeachDraftPreviewInspection
import com.bm.backend.models.TeachWorkbookAttachment
import com.bm.backend.models.TeachWorkbookCell
import com.bm.backend.models.TeachWorkbookInspection
import com.bm.backend.models.TeachWorkbookInspectionReceipt
import com.bm.backend.models.TeachWorkbookSheet
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeParseException
import java.util.Locale
import javax.xml.XMLConstants

private const val SHEET_NS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
private const val SHEET_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"
private const val STRINGS_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sharedstrings+xml"
private val ROW = Regex("[1-9][0-9]{0,6}")
private val A1 = Regex("([A-Z]{1,3})([1-9][0-9]{0,6})")
private val NUMBER = Regex("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?")
private val DATE = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}(?:T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\\.[0-9]{1,9})?Z?)?")
private val ERRORS = setOf("#NULL!", "#DIV/0!", "#VALUE!", "#REF!", "#NAME?", "#NUM!", "#N/A")
private val SHEET_STATES = setOf("visible", "hidden", "veryHidden")
private val NON_TEXT_HEADER_LITERALS = setOf("number", "date", "boolean")

internal class TeachWorkbookInspector(private val limits: TeachWorkbookInspectionLimits = TeachWorkbookInspectionLimits()) {
    fun inspect(
        validator: TeachWorkbookValidator, bytes: ByteArray, size: Int, filename: String, checkpoint: () -> Unit,
    ): ByteArray {
        val inspection = inspectStructure(validator, bytes, size, checkpoint)
        return encode(TeachWorkbookInspectionReceipt(TeachWorkbookAttachment("workbook", filename, size), inspection), checkpoint)
    }

    fun inspectForClarification(
        validator: TeachWorkbookValidator, bytes: ByteArray, size: Int, checkpoint: () -> Unit,
    ): TeachClarificationInspection {
        val visibleHeaders = mutableListOf<List<String>?>()
        val workbook = inspectStructure(validator, bytes, size, checkpoint, visibleHeaders)
        return TeachClarificationInspection(workbook, visibleHeaders)
    }

    fun inspectForDraftPreview(
        validator: TeachWorkbookValidator, bytes: ByteArray, checkpoint: () -> Unit,
    ): TeachDraftPreviewInspection {
        var result: TeachDraftPreviewInspection? = null
        inspectStructure(validator, bytes, bytes.size, checkpoint, preview = { result = it })
        return checkNotNull(result)
    }

    private fun inspectStructure(
        validator: TeachWorkbookValidator, bytes: ByteArray, size: Int, checkpoint: () -> Unit,
        visibleHeaders: MutableList<List<String>?>? = null,
        preview: ((TeachDraftPreviewInspection) -> Unit)? = null,
    ): TeachWorkbookInspection = validator.readPackage(bytes, size, checkpoint) { names, read ->
        val xml = TeachWorkbookInspectionXml(limits, checkpoint, read)
        validator.identify(names, exact = true,
            types = { xml.metadata(TYPES, TYPES_NS, "Types", it) },
            rels = { xml.metadata(RELS, RELS_NS, "Relationships", it) },
        ) { main, contentType -> inspectParts(xml, names, main, contentType, visibleHeaders, preview) }
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun encode(receipt: TeachWorkbookInspectionReceipt, checkpoint: () -> Unit): ByteArray {
        val bytes = ByteArrayOutputStream()
        val bounded = object : OutputStream() {
            private fun reserve(count: Int) {
                checkpoint()
                within(count <= limits.response - bytes.size())
            }
            override fun write(b: Int) { reserve(1); bytes.write(b) }
            override fun write(b: ByteArray, off: Int, len: Int) { reserve(len); bytes.write(b, off, len) }
        }
        Json.encodeToStream(TeachWorkbookInspectionReceipt.serializer(), receipt, bounded)
        checkpoint()
        return bytes.toByteArray()
    }

    private fun inspectParts(
        xml: TeachWorkbookInspectionXml, names: Set<String>, main: String, contentType: (String) -> String?,
        visibleHeaders: MutableList<List<String>?>?,
        preview: ((TeachDraftPreviewInspection) -> Unit)?,
    ): TeachWorkbookInspection {
        val sheets = mutableListOf<TeachWorkbookSheetBinding>()
        val sheetNames = hashSetOf<String>()
        val requiredIds = hashSetOf<String>()
        var dateSystem = "1900"
        var seenProperties = false
        var seenSheets = false
        xml.parse(main, SHEET_NS, "workbook") {
            elements {
                when (reader.localName) {
                    "workbookPr" -> {
                        expect("workbookPr")
                        workbookCheck(!seenProperties)
                        seenProperties = true
                        dateSystem = when (attr("date1904")) {
                            null, "0", "false" -> "1900"
                            "1", "true" -> "1904"
                            else -> throw InvalidWorkbook()
                        }
                        empty()
                    }
                    "sheets" -> {
                        expect("sheets")
                        workbookCheck(!seenSheets)
                        seenSheets = true
                        elements {
                            expect("sheet")
                            within(sheets.size < limits.sheets)
                            val name = required("name")
                            val state = attr("state") ?: "visible"
                            val id = reader.getAttributeValue(DOC_RELS_NS, "id")?.takeIf(String::isNotBlank)
                                ?: throw InvalidWorkbook()
                            workbookCheck(name.any { !isUnicodeSpace(it) } && name.codePointCount(0, name.length) <= 31 &&
                                name.toByteArray(Charsets.UTF_8).size <= 124 &&
                                name.none { it in ":\\/?*[]" || it.isISOControl() || isWorkbookBidi(it) || it == '\uFEFF' } &&
                                sheetNames.add(name.lowercase(Locale.ROOT)) && requiredIds.add(id) &&
                                state in SHEET_STATES)
                            sheets.add(TeachWorkbookSheetBinding(name, state, id))
                            empty()
                        }
                    }
                    else -> {
                        if (preview == null) skip() else {
                            previewCheck(reader.namespaceURI == SHEET_NS)
                            when (reader.localName) {
                                "workbookProtection", "definedNames" -> { previewCheck(reader.attributeCount == 0); empty() }
                                "calcPr" -> empty() // A setting, never an instruction to calculate.
                                "bookViews" -> elements {
                                    expect("workbookView")
                                    previewCheck(attr("visibility") in setOf(null, "visible"))
                                    empty()
                                }
                                else -> throw TeachDraftPreviewConflict()
                            }
                        }
                    }
                }
            }
            workbookCheck(seenSheets && sheets.isNotEmpty())
        }
        if (preview != null) previewCheck(sheets.size == 1 && dateSystem == "1900")
        val directory = main.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
        val relationships = "${directory}_rels/${main.substringAfterLast('/')}.rels"
        val used = hashSetOf(main, relationships, TYPES, RELS)
        val ids = hashSetOf<String>()
        val targets = mutableMapOf<String, String>()
        var stringsPart: String? = null
        var stylesPart: String? = null
        xml.parse(relationships, RELS_NS, "Relationships") {
            elements {
                expect("Relationship", RELS_NS)
                val id = required("Id")
                val type = required("Type")
                val target = required("Target")
                workbookCheck(ids.add(id) && "vba" !in type.lowercase(Locale.ROOT))
                val sheet = id in requiredIds
                if (preview != null) previewCheck(attr("TargetMode") in setOf(null, "Internal"))
                if (sheet || type == "$DOC_RELS_NS/sharedStrings") {
                    workbookCheck(!sheet || type == "$DOC_RELS_NS/worksheet")
                    val mode = attr("TargetMode")
                    workbookCheck(mode == null || mode == "Internal")
                    val part = if (target.startsWith('/')) target.drop(1) else directory + target
                    workbookCheck(safeWorkbookPart(part) && !part.endsWith('/') && part in names && used.add(part))
                    workbookCheck(contentType(part) == if (sheet) SHEET_TYPE else STRINGS_TYPE)
                    if (sheet) targets[id] = part else {
                        workbookCheck(stringsPart == null)
                        stringsPart = part
                    }
                } else if (preview != null) when (type) {
                    "$DOC_RELS_NS/styles" -> {
                        val part = if (target.startsWith('/')) target.drop(1) else directory + target
                        previewCheck(stylesPart == null && safeWorkbookPart(part) && part in names && used.add(part) &&
                            contentType(part) == "application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml")
                        stylesPart = part
                    }
                    "$DOC_RELS_NS/theme" -> Unit // Not consulted; the pinned General number format does not depend on it.
                    else -> throw TeachDraftPreviewConflict()
                }
                empty()
            }
        }
        val styleDigest = if (preview != null) {
            val part = stylesPart ?: throw TeachDraftPreviewConflict()
            sha256Hex(MessageDigest.getInstance("SHA-256").digest(xml.readBoundedPart(part)))
        } else null
        workbookCheck(targets.keys == requiredIds)
        val strings = stringsPart?.let { part ->
            xml.parse(part, SHEET_NS, "sst") {
                val entries = mutableListOf<String>()
                elements {
                    if (reader.localName == "extLst") {
                        expect("extLst")
                        skip()
                    } else {
                        expect("si")
                        within(entries.size < limits.strings)
                        entries.add(richText())
                    }
                }
                entries
            }
        }.orEmpty()
        var totalCells = 0
        val inspected = sheets.map { sheet ->
            val visibility = if (visibleHeaders != null && sheet.state == "visible") TeachClarificationVisibility(SHEET_NS) else null
            val previewMetadata = if (preview != null) TeachDraftPreviewMetadata(SHEET_NS) else null
            val inspectedSheet = xml.parse(targets.getValue(sheet.relationshipId), SHEET_NS, "worksheet") {
                worksheet(sheet, strings, visibility, previewMetadata) { within(++totalCells <= limits.cells) }
            }
            visibleHeaders?.add(if (visibility != null) visibility.result() else emptyList())
            if (previewMetadata != null) checkNotNull(preview)(previewMetadata.finish(inspectedSheet, checkNotNull(styleDigest)))
            inspectedSheet
        }
        return TeachWorkbookInspection(1, dateSystem, inspected)
    }

    private fun TeachWorkbookInspectionXml.Cursor.worksheet(
        sheet: TeachWorkbookSheetBinding, strings: List<String>, visibility: TeachClarificationVisibility?,
        preview: TeachDraftPreviewMetadata?, countCell: () -> Unit,
    ): TeachWorkbookSheet {
        val cells = mutableListOf<TeachWorkbookCell>()
        var headers: List<String>? = null
        var sheetData = false
        elements {
            if (reader.localName != "sheetData") {
                if (preview != null) preview.metadata(this, sheetData)
                else if (visibility != null) visibility.skipMetadata(this, sheetData) else skip()
            } else {
                expect("sheetData")
                workbookCheck(!sheetData)
                sheetData = true
                var previousRow = 0
                var rows = 0
                elements {
                    expect("row")
                    within(++rows <= limits.rows)
                    val rowRef = required("r")
                    workbookCheck(ROW.matches(rowRef))
                    val row = rowRef.toInt()
                    if (preview != null) previewCheck(row > previousRow)
                    workbookCheck(row in (previousRow + 1)..1_048_576)
                    preview?.row(reader, row)
                    previousRow = row
                    var previousCol = 0
                    val firstCell = cells.size
                    val visibleHeaderRow = visibility?.takeIf { it.rowVisible(reader) && it.headers == null }
                    val visibleCells = visibleHeaderRow?.let { mutableListOf<TeachWorkbookCell>() }
                    elements {
                        if (reader.localName == "extLst") {
                            if (preview != null) throw TeachDraftPreviewConflict()
                            expect("extLst")
                            skip()
                        } else {
                            expect("c")
                            countCell()
                            val ref = required("r")
                            val match = A1.matchEntire(ref) ?: throw InvalidWorkbook()
                            val col = match.groupValues[1].fold(0) { acc, c -> acc * 26 + (c - 'A' + 1) }
                            if (preview != null) previewCheck(col > previousCol && match.groupValues[2] == rowRef)
                            workbookCheck(col in (previousCol + 1)..16_384 && match.groupValues[2] == rowRef)
                            previousCol = col
                            preview?.cell(reader, row, col, ref)
                            val parsed = cell(ref, strings)
                            cells.add(parsed)
                            if (visibleCells != null && visibleHeaderRow.columnVisible(col)) visibleCells.add(parsed)
                        }
                    }
                    if (headers == null) headers = headerCoordinates(cells.subList(firstCell, cells.size))
                    if (visibleCells != null) visibleHeaderRow.headers = headerCoordinates(visibleCells)
                }
            }
        }
        workbookCheck(sheetData)
        return TeachWorkbookSheet(sheet.name, sheet.state, headers.orEmpty(), cells)
    }

    private fun TeachWorkbookInspectionXml.Cursor.cell(a1: String, strings: List<String>): TeachWorkbookCell {
        val type = attr("t") ?: "n"
        var hasFormula = false
        var inline: String? = null
        var value: String? = null
        // In the worksheet grammar f precedes v/is. Defer all cache handling to the formula marker.
        elements {
            when (reader.localName) {
                "f" -> {
                    expect("f")
                    workbookCheck(!hasFormula && value == null && inline == null)
                    hasFormula = true
                    scalar()
                }
                "v" -> {
                    expect("v")
                    workbookCheck(value == null && inline == null)
                    value = if (hasFormula) { scalar(); "" } else textValue()
                }
                "is" -> {
                    expect("is")
                    workbookCheck(inline == null && value == null && !hasFormula)
                    inline = richText()
                }
                "extLst" -> { expect("extLst"); skip() }
                else -> throw InvalidWorkbook()
            }
        }
        if (hasFormula) return TeachWorkbookCell(a1, "formula")
        workbookCheck(inline == null || type == "inlineStr")
        val literal = value
        fun text(kind: String, text: String?) = TeachWorkbookCell(a1, kind, JsonPrimitive(text ?: throw InvalidWorkbook()))
        return when (type) {
            "n" -> if (literal.isNullOrEmpty()) TeachWorkbookCell(a1, "blank") else {
                workbookCheck(NUMBER.matches(literal))
                within(literal.length <= 64) // A matching number is ASCII, so chars == bytes.
                text("number", literal)
            }
            "d" -> {
                workbookCheck(literal != null && validDate(literal))
                text("date", literal)
            }
            "s" -> {
                workbookCheck(!literal.isNullOrEmpty() && literal.all { it in '0'..'9' })
                val index = checkNotNull(literal).trimStart('0').ifEmpty { "0" }.toIntOrNull() ?: throw InvalidWorkbook()
                text("text", strings.getOrNull(index))
            }
            "str" -> text("text", literal)
            "inlineStr" -> text("text", inline)
            "e" -> {
                workbookCheck(literal in ERRORS)
                text("error", literal)
            }
            "b" -> {
                workbookCheck(literal == "0" || literal == "1")
                TeachWorkbookCell(a1, "boolean", JsonPrimitive(literal == "1"))
            }
            else -> throw InvalidWorkbook()
        }
    }

    private fun TeachWorkbookInspectionXml.Cursor.textValue() = BoundedText(limits.text).apply { scalar(::append) }.value()

    private fun TeachWorkbookInspectionXml.Cursor.richText(): String {
        val text = BoundedText(limits.text)
        var simple = false
        var rich = false
        elements {
            when (reader.localName) {
                "t" -> {
                    expect("t")
                    workbookCheck(!simple && !rich)
                    simple = true
                    scalar(text::append)
                }
                "r" -> {
                    expect("r")
                    workbookCheck(!simple)
                    rich = true
                    var seenText = false
                    var seenProperties = false
                    elements {
                        when (reader.localName) {
                            "rPr" -> {
                                expect("rPr")
                                workbookCheck(!seenProperties && !seenText)
                                seenProperties = true
                                skip()
                            }
                            "t" -> {
                                expect("t")
                                workbookCheck(!seenText)
                                seenText = true
                                scalar(text::append)
                            }
                            else -> throw InvalidWorkbook()
                        }
                    }
                    workbookCheck(seenText)
                }
                "rPh", "phoneticPr" -> { expect(reader.localName); skip() }
                else -> throw InvalidWorkbook()
            }
        }
        return text.value()
    }

    private fun TeachWorkbookInspectionXml.Cursor.expect(name: String, ns: String = SHEET_NS) =
        workbookCheck(reader.localName == name && reader.namespaceURI == ns)

    private fun TeachWorkbookInspectionXml.Cursor.attr(name: String): String? =
        reader.getAttributeValue(XMLConstants.NULL_NS_URI, name)

    private fun TeachWorkbookInspectionXml.Cursor.required(name: String): String =
        attr(name)?.takeIf(String::isNotBlank) ?: throw InvalidWorkbook()
}

/** null: keep looking; empty: a literal row exists but has no text headers. */
private fun headerCoordinates(row: List<TeachWorkbookCell>): List<String>? {
    fun text(cell: TeachWorkbookCell) = cell.kind == "text" && cell.value!!.content.any { !isUnicodeSpace(it) }
    return if (row.any { it.kind in NON_TEXT_HEADER_LITERALS || text(it) }) row.filter(::text).map { it.a1 } else null
}

private fun validDate(text: String): Boolean {
    if (!DATE.matches(text) || text.startsWith("0000")) return false
    return try {
        if (text.length == 10) LocalDate.parse(text) else LocalDateTime.parse(text.removeSuffix("Z"))
        true
    } catch (_: DateTimeParseException) { false }
}

/** Unicode White_Space, not Java's narrower isWhitespace or Kotlin's broader union. */
internal fun isUnicodeSpace(c: Char) = c in '\u0009'..'\u000d' || c in '\u2000'..'\u200a' ||
    c in " \u0085\u00a0\u1680\u2028\u2029\u202f\u205f\u3000"

private fun within(allowed: Boolean) { if (!allowed) throw WorkbookBudgetExceeded() }

private class BoundedText(private val limit: Int) {
    private val text = StringBuilder()
    private var bytes = 0
    private var high = false

    fun append(chunk: String) {
        for (c in chunk) {
            if (high) {
                workbookCheck(c.isLowSurrogate())
                high = false
            } else {
                workbookCheck(!c.isLowSurrogate())
                bytes += when {
                    c.isHighSurrogate() -> { high = true; 4 }
                    c <= '\u007f' -> 1
                    c <= '\u07ff' -> 2
                    else -> 3
                }
                within(bytes <= limit)
            }
        }
        text.append(chunk)
    }

    fun value(): String {
        workbookCheck(!high)
        return text.toString()
    }
}
