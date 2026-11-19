package com.bm.backend.services

import java.util.BitSet
import javax.xml.stream.XMLStreamConstants.*
import javax.xml.stream.XMLStreamReader

private val COLUMN_NUMBER = Regex("\\+?[0-9]+")
private val SIZE = Regex("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?")
private val VISIBILITY_ELEMENTS = setOf("cols", "col", "row", "sheetData", "sheetFormatPr")
private const val COMPATIBILITY_NS = "http://schemas.openxmlformats.org/markup-compatibility/2006"

/** Narrow clarification-only interpretation. Unknown visibility invalidates candidate selection, never inspection. */
internal class TeachClarificationVisibility(private val sheetNamespace: String) {
    private val definedColumns = BitSet(16_385)
    private val hiddenColumns = BitSet(16_385)
    private var known = true
    private var seenDefaults = false
    var headers: List<String>? = null
    fun result(): List<String>? = if (known) headers.orEmpty() else null
    fun isKnown(): Boolean = known

    fun rowVisible(reader: XMLStreamReader): Boolean {
        val hidden = boolean(reader, "hidden")
        val zeroHeight = zeroSize(reader, "ht")
        if (boolean(reader, "collapsed")) known = false
        return known && !hidden && !zeroHeight
    }

    fun columnVisible(column: Int): Boolean = known && !hiddenColumns[column]

    fun skipMetadata(cursor: TeachWorkbookInspectionXml.Cursor, afterSheetData: Boolean, checkColumn: (XMLStreamReader) -> Unit = {}) = with(cursor) {
        when (reader.localName) {
            "cols" -> {
                if (afterSheetData || reader.namespaceURI != sheetNamespace) known = false
                val containerDepth = depth
                var columns = 0
                skip {
                    when (reader.eventType) {
                        START_ELEMENT -> {
                            if (depth != containerDepth + 1 || reader.localName != "col" || reader.namespaceURI != sheetNamespace) {
                                known = false
                            } else {
                                columns++
                                checkColumn(reader)
                                column(reader)
                            }
                        }
                        CHARACTERS, SPACE, CDATA -> if (!reader.isWhiteSpace) known = false
                    }
                }
                if (columns == 0) known = false
            }
            "sheetFormatPr" -> {
                if (afterSheetData || seenDefaults || reader.namespaceURI != sheetNamespace) known = false
                seenDefaults = true
                if (boolean(reader, "zeroHeight") || zeroSize(reader, "defaultRowHeight") ||
                    zeroSize(reader, "defaultColWidth") || zeroSize(reader, "baseColWidth")) known = false
                skip {
                    when (reader.eventType) {
                        START_ELEMENT -> known = false
                        CHARACTERS, SPACE, CDATA -> if (!reader.isWhiteSpace) known = false
                    }
                }
            }
            "col", "row", "AlternateContent", "customSheetViews" -> { known = false; skip() }
            else -> skip {
                if (reader.eventType == START_ELEMENT &&
                    ((reader.namespaceURI == sheetNamespace && reader.localName in VISIBILITY_ELEMENTS) ||
                        (reader.namespaceURI == COMPATIBILITY_NS && reader.localName == "AlternateContent"))) known = false
            }
        }
    }

    private fun column(reader: XMLStreamReader) {
        val first = columnNumber(reader, "min")
        val last = columnNumber(reader, "max")
        val hidden = boolean(reader, "hidden")
        val zeroWidth = zeroSize(reader, "width")
        if (boolean(reader, "collapsed")) known = false
        if (first == null || last == null || first > last || definedColumns.nextSetBit(first) in first..last) {
            known = false
        } else {
            definedColumns.set(first, last + 1)
            if (hidden || zeroWidth) hiddenColumns.set(first, last + 1)
        }
    }

    private fun columnNumber(reader: XMLStreamReader, name: String): Int? = attribute(reader, name)
        ?.takeIf(COLUMN_NUMBER::matches)?.toIntOrNull()?.takeIf { it in 1..16_384 }

    private fun boolean(reader: XMLStreamReader, name: String): Boolean = when (attribute(reader, name)) {
        null, "0", "false" -> false
        "1", "true" -> true
        else -> { known = false; false }
    }

    private fun zeroSize(reader: XMLStreamReader, name: String): Boolean {
        val value = attribute(reader, name) ?: return false
        val size = value.takeIf(SIZE::matches)?.toDoubleOrNull()
        if (size == null || !size.isFinite() || size < 0) { known = false; return false }
        return size == 0.0
    }

    private fun attribute(reader: XMLStreamReader, name: String): String? {
        if ((0 until reader.attributeCount).any { reader.getAttributeLocalName(it) == name && !reader.getAttributeNamespace(it).isNullOrEmpty() }) {
            known = false
        }
        return reader.getAttributeValue(null, name)?.trim(' ', '\t', '\r', '\n')
    }
}
