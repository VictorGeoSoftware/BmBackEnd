package com.bm.backend.testing

internal const val SYNTHETIC_SUPPLIER_MARKER = "BM SYNTHETIC SUPPLIER"

internal fun markerText(ref: String, text: String) = """<c r="$ref" t="inlineStr"><is><t xml:space="preserve">$text</t></is></c>"""

internal fun markerRow(row: Int, supplier: String, attributes: String = "") =
    """<row r="$row" $attributes>${markerText("A$row", SYNTHETIC_SUPPLIER_MARKER)}${markerText("B$row", supplier)}</row>"""

internal fun supplierMarkerWorkbook(
    rows: String = markerRow(1, "gesternova"),
    sheet: String = "BM Identity",
    sheetAttributes: String = "",
    beforeRows: String = "",
    afterRows: String = "",
    workbookMetadata: String = "",
    exactSize: Int? = null,
    relationshipType: String? = "$DOC_RELS_NS/worksheet",
    relationshipTarget: String? = "tables/first.xml",
    sharedStrings: String? = null,
    sheetBody: String = "",
    relationshipBody: String = "",
): ByteArray {
    val parts = bookParts().apply {
        val stringType = if (sharedStrings == null) "" else
            """<Override PartName="/$STRING_TABLE" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sharedStrings+xml"/>"""
        put(IDENT_TYPES, bookTypes().replace("</Types>", """<Override PartName="/$FIRST_SHEET" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>$stringType</Types>""").encodeToByteArray())
        put("xl/workbook.xml", """<workbook xmlns="$SHEET_NS" xmlns:r="$DOC_RELS_NS">$workbookMetadata
            <sheets><sheet name="$sheet" sheetId="1" r:id="identity" $sheetAttributes>$sheetBody</sheet></sheets></workbook>""".encodeToByteArray())
        val type = relationshipType?.let { """ Type="$it"""" }.orEmpty()
        val target = relationshipTarget?.let { """ Target="$it"""" }.orEmpty()
        val strings = if (sharedStrings == null) "" else
            """<Relationship Id="strings" Type="$DOC_RELS_NS/sharedStrings" Target="tables/strings.xml"/>"""
        put(WORKBOOK_LINKS, """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
            <Relationship Id="identity"$type$target>$relationshipBody</Relationship>$strings</Relationships>""".encodeToByteArray())
        if (sharedStrings != null) put(STRING_TABLE, sharedStrings.encodeToByteArray())
        put(FIRST_SHEET, """<worksheet xmlns="$SHEET_NS">$beforeRows<sheetData>$rows</sheetData>$afterRows</worksheet>""".encodeToByteArray())
    }
    if (exactSize != null) {
        parts["xl/synthetic.txt"] = byteArrayOf()
        parts["xl/synthetic.txt"] = ByteArray(exactSize - syntheticZip(parts).size)
    }
    return syntheticZip(parts)
}

internal val validSupplierSharedStrings = """<sst xmlns="$SHEET_NS"><si><t>gesternova</t></si></sst>"""
internal val malformedSupplierSharedStrings = """<sst xmlns="$SHEET_NS"><si><t>gesternova</si></sst>"""

internal fun sharedSupplierMarkerRow(index: String = "0", ref: String = "B1"): String {
    val inlineMarker = if (ref == "B1") markerText("A1", SYNTHETIC_SUPPLIER_MARKER) else
        markerText("A1", SYNTHETIC_SUPPLIER_MARKER) + markerText("B1", "gesternova")
    return """<row r="1">$inlineMarker<c r="$ref" t="s"><v>$index</v></c></row>"""
}
