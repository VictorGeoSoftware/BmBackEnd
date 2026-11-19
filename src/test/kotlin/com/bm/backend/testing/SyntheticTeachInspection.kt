package com.bm.backend.testing

internal const val SHEET_NS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
internal const val DOC_RELS_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
internal const val FIRST_SHEET = "xl/tables/first.xml"
internal const val SECOND_SHEET = "pages/second.xml"
internal const val STRING_TABLE = "xl/tables/strings.xml"
internal const val WORKBOOK_LINKS = "xl/_rels/workbook.xml.rels"

internal fun worksheet(rows: String) = """<worksheet xmlns="$SHEET_NS"><sheetData>$rows</sheetData></worksheet>"""

internal fun inspectionParts(date1904: String = "true") = bookParts().apply {
    fun xml(part: String, data: String) { put(part, data.toByteArray()) }
    xml(IDENT_TYPES, bookTypes().replace("</Types>", """
        <Override PartName="/$FIRST_SHEET" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
        <Override PartName="/$SECOND_SHEET" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
        <Override PartName="/$STRING_TABLE" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sharedStrings+xml"/>
        </Types>
    """.trimIndent()))
    xml("xl/workbook.xml", """<workbook xmlns="$SHEET_NS" xmlns:r="$DOC_RELS_NS">
        <workbookPr date1904="$date1904"/><sheets>
        <sheet name="Muestra Uno" sheetId="1" r:id="first"/><sheet name="Muestra Dos" sheetId="2" r:id="second" state="hidden"/>
        </sheets></workbook>""")
    xml(WORKBOOK_LINKS, """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
        <Relationship Id="first" Type="$DOC_RELS_NS/worksheet" Target="tables/first.xml"/>
        <Relationship Id="second" Type="$DOC_RELS_NS/worksheet" TargetMode="Internal" Target="/$SECOND_SHEET"/>
        <Relationship Id="strings" Type="$DOC_RELS_NS/sharedStrings" Target="tables/strings.xml"/>
        <Relationship Id="ignored" Type="$DOC_RELS_NS/externalLink" TargetMode="External" Target="https://example.invalid/ignored"/>
        </Relationships>""")
    xml(FIRST_SHEET, worksheet("""
        <row r="1"><c r="A1"><f/></c><c r="B1" t="e"><v>#N/A</v></c></row>
        <row r="2"><c r="A2" t="s"><v>0</v></c><c r="AA2" t="inlineStr"><is><t> Tarifa &amp; luz </t></is></c></row>
        <row r="3"><c r="A3"><v>0</v></c><c r="C3"/><c r="D3" t="str"><v/></c>
        <c r="E3" t="n"><f t="shared" si="0">1+1</f><v>2</v></c><c r="F3"><f t="shared" si="0"/></c>
        <c r="G3" t="s"><v>1</v></c><c r="H3" t="s"><v>2</v></c><c r="I3"><v>+0.1234567890123456789e-4</v></c>
        <c r="J3" t="d"><v>2024-02-29</v></c><c r="K3" t="d"><v>0001-01-01T22:03:04.012345678Z</v></c>
        <c r="L3" s="1"><v>60</v></c><c r="M3" t="b"><v>1</v></c></row>
    """))
    xml(SECOND_SHEET, worksheet("""<row r="3"><c r="A3"><v>41</v></c><c r="B3" t="str"><v>&#x85;</v></c></row>
        <row r="4"><c r="A4" t="str"><v>No candidate</v></c></row>"""))
    xml(STRING_TABLE, """<sst xmlns="$SHEET_NS" count="99"><si><t> Título </t></si>
        <si><r><rPr><b/></rPr><t xml:space="preserve"> Con </t></r><r><t>saltos&#10;😀</t></r>
        <rPh sb="0" eb="1"><t>ignored</t></rPh></si><si><t/></si></sst>""")
    // Opaque worksheet relationships must be integrity checked but never parsed.
    xml("xl/tables/_rels/first.xml.rels", "opaque synthetic bytes")
}

internal fun inspectionWorkbookOfSize(size: Int): ByteArray {
    val parts = inspectionParts().apply { put("synthetic.txt", byteArrayOf()) }
    parts["synthetic.txt"] = ByteArray(size - syntheticZip(parts).size)
    return syntheticZip(parts).also { check(it.size == size) }
}
