package com.bm.backend.services

import com.bm.backend.models.TeachDraftPreviewInspection
import com.bm.backend.models.TeachWorkbookSheet
import javax.xml.stream.XMLStreamReader

/** Additional worksheet proof only requested by V2. Nothing becomes visible by inference from headers. */
internal class TeachDraftPreviewMetadata(private val namespace: String) {
    private val visibility = TeachClarificationVisibility(namespace)
    private val rows = mutableMapOf<Int, Boolean>()
    private val visible = mutableSetOf<String>()
    private val defaultStyle = mutableSetOf<String>()
    private val seen = mutableSetOf<String>()

    fun row(reader: XMLStreamReader, row: Int) {
        attributes(reader, ROW_ATTRIBUTES)
        rows[row] = visibility.rowVisible(reader)
    }

    fun cell(reader: XMLStreamReader, row: Int, column: Int, ref: String) {
        if (rows[row] == true && visibility.columnVisible(column)) visible.add(ref)
        if (attributesMatch(reader, CELL_ATTRIBUTES) && reader.getAttributeValue(null, "s") in setOf(null, "0")) {
            defaultStyle.add(ref)
        }
    }

    fun metadata(cursor: TeachWorkbookInspectionXml.Cursor, afterSheetData: Boolean) = with(cursor) {
        val name = reader.localName
        previewCheck(reader.namespaceURI == namespace && seen.add(name))
        when (name) {
            "sheetFormatPr" -> {
                attributes(reader, FORMAT_ATTRIBUTES)
                visibility.skipMetadata(this, afterSheetData)
            }
            "cols" -> visibility.skipMetadata(this, afterSheetData) { attributes(it, COLUMN_ATTRIBUTES) }
            "sheetPr" -> {
                attributes(reader, emptySet())
                val children = mutableSetOf<String>()
                elements {
                    previewCheck(reader.namespaceURI == namespace && children.add(reader.localName))
                    when (reader.localName) {
                        "outlinePr" -> attributes(reader, setOf("summaryBelow", "summaryRight"))
                        "pageSetUpPr" -> attributes(reader, emptySet())
                        else -> throw TeachDraftPreviewConflict()
                    }
                    empty()
                }
            }
            "sheetViews" -> elements {
                previewCheck(reader.namespaceURI == namespace && reader.localName == "sheetView" && seen.add("sheetView"))
                attributes(reader, setOf("workbookViewId"))
                elements {
                    previewCheck(reader.namespaceURI == namespace && reader.localName == "selection" && seen.add("selection"))
                    attributes(reader, setOf("activeCell", "sqref"))
                    empty()
                }
            }
            "dimension" -> { attributes(reader, setOf("ref")); empty() }
            "pageMargins" -> { attributes(reader, MARGINS); empty() }
            // Merges, filters, alternate views, conditional formats, extensions, etc. are not in the profile.
            else -> throw TeachDraftPreviewConflict()
        }
    }

    fun finish(sheet: TeachWorkbookSheet, stylesSha256: String): TeachDraftPreviewInspection {
        previewCheck(visibility.isKnown() && sheet.state == "visible")
        return TeachDraftPreviewInspection(sheet, rows, visible, defaultStyle, stylesSha256)
    }

    private fun attributes(reader: XMLStreamReader, allowed: Set<String>) = previewCheck(attributesMatch(reader, allowed))

    private fun attributesMatch(reader: XMLStreamReader, allowed: Set<String>) = (0 until reader.attributeCount).all {
        reader.getAttributeNamespace(it).isNullOrEmpty() && reader.getAttributeLocalName(it) in allowed
    }

    private companion object {
        val ROW_ATTRIBUTES = setOf("r", "hidden", "ht", "customHeight", "collapsed", "outlineLevel", "spans")
        val COLUMN_ATTRIBUTES = setOf("min", "max", "hidden", "width", "customWidth", "collapsed", "outlineLevel")
        val CELL_ATTRIBUTES = setOf("r", "t", "s")
        val FORMAT_ATTRIBUTES = setOf("baseColWidth", "defaultColWidth", "defaultRowHeight", "zeroHeight", "customHeight")
        val MARGINS = setOf("left", "right", "top", "bottom", "header", "footer")
    }
}
