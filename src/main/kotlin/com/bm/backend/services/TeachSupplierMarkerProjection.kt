package com.bm.backend.services

import com.bm.backend.models.TeachWorkbookCell
import javax.xml.stream.XMLStreamConstants.START_ELEMENT
import javax.xml.stream.XMLStreamReader

internal const val SUPPLIER_MARKER_SHEET = "BM Identity"
private val markerCells = setOf("A1", "B1", "A2", "B2")
private val unsupportedNestedMetadata = setOf("AlternateContent", "mergeCells", "mergeCell")
private val evidenceElements = setOf("sheetData", "row", "c")
private val cellRange = Regex("([A-Z]{1,3})([1-9][0-9]{0,6})(?::([A-Z]{1,3})([1-9][0-9]{0,6}))?")

/** Prove the visibility and literal provenance of exactly four coordinates, not header candidates. */
internal class TeachSupplierMarkerProjection(private val ns: String) {
    private val visibility = TeachClarificationVisibility(ns)
    private val rowVisible = mutableMapOf<Int, Boolean>()
    private val literals = mutableMapOf<String, String?>()
    private var seenMerges = false

    fun row(reader: XMLStreamReader, row: Int) {
        if (row <= 2) rowVisible[row] = visibility.rowVisible(reader)
    }

    fun includes(ref: String) = ref in markerCells

    fun cell(reader: XMLStreamReader, cell: TeachWorkbookCell, type: String?) {
        if (!includes(cell.a1)) return
        // `str` is OOXML's formula-string result type, not an inline or shared text literal.
        if (type == "str" || cell.a1 in literals || reader.namespaceURI != ns) invalidSupplierEvidence()
        literals[cell.a1] = when (cell.kind) {
            "blank" -> null
            "text" -> cell.value?.content?.ifEmpty { null }
            else -> invalidSupplierEvidence()
        }
    }

    fun formula(reader: XMLStreamReader) {
        // An array/data-table formula can own cells other than its anchor.
        reader.getAttributeValue("", "ref")?.let { if (overlapsMarkers(it)) invalidSupplierEvidence() }
    }

    fun metadata(cursor: TeachWorkbookInspectionXml.Cursor, afterSheetData: Boolean) = with(cursor) {
        when (reader.localName) {
            "mergeCells" -> {
                if (!afterSheetData || seenMerges || reader.namespaceURI != ns) invalidSupplierEvidence()
                seenMerges = true
                elements {
                    if (reader.localName != "mergeCell" || reader.namespaceURI != ns ||
                        overlapsMarkers(reader.getAttributeValue("", "ref"))) invalidSupplierEvidence()
                    empty()
                }
            }
            "mergeCell", "extLst" -> invalidSupplierEvidence()
            else -> visibility.skipMetadata(this, afterSheetData, onNested = ::rejectNestedEvidence)
        }
    }

    fun rejectNestedEvidence(reader: XMLStreamReader) {
        if (reader.eventType == START_ELEMENT && (reader.localName in unsupportedNestedMetadata ||
                reader.namespaceURI == ns && reader.localName in evidenceElements)) invalidSupplierEvidence()
    }

    fun finish(): List<Pair<String?, String?>> {
        if (!visibility.isKnown()) invalidSupplierEvidence()
        return (1..2).map { row ->
            val a = literals["A$row"]
            val b = literals["B$row"]
            if ((a != null || b != null) &&
                (rowVisible[row] != true || !visibility.columnVisible(1) || !visibility.columnVisible(2))) invalidSupplierEvidence()
            a to b
        }
    }

    private fun overlapsMarkers(ref: String?): Boolean {
        val groups = ref?.let(cellRange::matchEntire)?.groupValues ?: invalidSupplierEvidence()
        fun col(s: String) = s.fold(0) { n, c -> n * 26 + (c - 'A' + 1) }
        val firstCol = col(groups[1])
        val firstRow = groups[2].toInt()
        val lastCol = col(groups[3].ifEmpty { groups[1] })
        val lastRow = groups[4].ifEmpty { groups[2] }.toInt()
        if (lastCol !in firstCol..16_384 || lastRow !in firstRow..1_048_576) invalidSupplierEvidence()
        return firstCol <= 2 && firstRow <= 2
    }
}
