package com.bm.backend.services

import com.bm.backend.testing.*
import java.io.InputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipFile
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel
import org.junit.jupiter.api.Test
import kotlin.test.*

class TeachWorkbookInspectorTest {
    private fun inspect(bytes: ByteArray, limits: TeachWorkbookInspectionLimits = TeachWorkbookInspectionLimits(),
                        validator: TeachWorkbookValidator = TeachWorkbookValidator()) =
        TeachWorkbookInspector(limits).inspect(validator, bytes, bytes.size, "sintético.xlsx") {}

    private fun MutableMap<String, ByteArray>.replace(part: String, from: String, to: String) {
        val xml = getValue(part).decodeToString()
        check(from in xml)
        put(part, xml.replace(from, to).toByteArray())
    }

    @Test fun `exact deterministic wire preserves original literals coordinates and workbook order`() {
        val workbook = syntheticZip(inspectionParts(), deflated = true)
        val output = inspect(workbook)
        assertContentEquals(output, inspect(workbook))
        val expected = """
            {"attachment":{"kind":"workbook","filename":"sintético.xlsx","sizeBytes":${workbook.size}},"inspection":{"version":1,"dateSystem":"1904","sheets":[
            {"name":"Muestra Uno","state":"visible","headers":["A2","AA2"],"cells":[
            {"a1":"A1","kind":"formula"},{"a1":"B1","kind":"error","value":"#N/A"},
            {"a1":"A2","kind":"text","value":" Título "},{"a1":"AA2","kind":"text","value":" Tarifa & luz "},
            {"a1":"A3","kind":"number","value":"0"},{"a1":"C3","kind":"blank"},{"a1":"D3","kind":"text","value":""},
            {"a1":"E3","kind":"formula"},{"a1":"F3","kind":"formula"},
            {"a1":"G3","kind":"text","value":" Con saltos\n😀"},{"a1":"H3","kind":"text","value":""},
            {"a1":"I3","kind":"number","value":"+0.1234567890123456789e-4"},
            {"a1":"J3","kind":"date","value":"2024-02-29"},{"a1":"K3","kind":"date","value":"0001-01-01T22:03:04.012345678Z"},
            {"a1":"L3","kind":"number","value":"60"},{"a1":"M3","kind":"boolean","value":true}]},
            {"name":"Muestra Dos","state":"hidden","headers":[],"cells":[
            {"a1":"A3","kind":"number","value":"41"},{"a1":"B3","kind":"text","value":"${'\u0085'}"},
            {"a1":"A4","kind":"text","value":"No candidate"}]}]}}
        """.trimIndent().replace("\n", "")
        assertEquals(expected, output.decodeToString())
        assertEquals(setOf("attachment", "inspection"), Json.parseToJsonElement(expected).jsonObject.keys)
        for (epoch in listOf("false", "0", "absent")) {
            val parts = inspectionParts(epoch)
            if (epoch == "absent") parts.replace("xl/workbook.xml", "<workbookPr date1904=\"absent\"/>", "")
            val text = inspect(syntheticZip(parts)).decodeToString()
            assertContains(text, "\"dateSystem\":\"1900\"")
            assertContains(text, """{"a1":"L3","kind":"number","value":"60"}""")
        }
    }

    @Test fun `required parts are resolved from the actual workbook location and everything is consumed once`() {
        val parts = inspectionParts().apply {
            put("xl/renamed.xml", remove("xl/workbook.xml")!!)
            put("xl/_rels/renamed.xml.rels", remove(WORKBOOK_LINKS)!!)
            replace(IDENT_TYPES, "xl/workbook.xml", "xl/renamed.xml")
            replace(IDENT_RELS, "xl/workbook.xml", "/xl/renamed.xml")
            replace("xl/renamed.xml", "hidden", "veryHidden")
            replace(FIRST_SHEET, "</worksheet>", "<mergeCells><mergeCell ref=\"A2:B2\"/></mergeCells></worksheet>")
        }
        val handled = mutableListOf<String>()
        @Suppress("DEPRECATION")
        val validator = TeachWorkbookValidator(open = { bytes, size, _ ->
            object : ZipFile(SeekableInMemoryByteChannel(bytes).truncate(size.toLong())) {
                override fun getRawInputStream(entry: ZipArchiveEntry): InputStream? {
                    handled.add(entry.name)
                    return super.getRawInputStream(entry)
                }
            }
        })
        val result = inspect(syntheticZip(parts, deflated = true), validator = validator).decodeToString()
        assertContains(result, "\"state\":\"veryHidden\"")
        assertFalse("example.invalid" in result || "renamed.xml" in result)
        assertEquals(parts.keys, handled.toSet())
        assertEquals(parts.size, handled.size)
        assertEquals("xl/tables/_rels/first.xml.rels", handled.last())
    }

    @Test fun `rejects invalid mapping duplicate names workbook settings and wrong part type`() {
        val mutators = listOf(
            Triple(WORKBOOK_LINKS, "tables/first.xml", "Tables/first.xml"),
            Triple(WORKBOOK_LINKS, "tables/first.xml", "./tables/first.xml"),
            Triple(WORKBOOK_LINKS, "tables/first.xml", "tables/first.xml#fragment"),
            Triple(WORKBOOK_LINKS, "tables/first.xml", "tables/%66irst.xml"),
            Triple(WORKBOOK_LINKS, "Target=\"tables/first.xml\"", "TargetMode=\"External\" Target=\"https://example.invalid/sheet\""),
            Triple(WORKBOOK_LINKS, "Id=\"second\"", "Id=\"first\""),
            Triple(WORKBOOK_LINKS, "/$SECOND_SHEET", "/$FIRST_SHEET"),
            Triple(WORKBOOK_LINKS, "Target=\"tables/strings.xml\"", "TargetMode=\"External\" Target=\"https://example.invalid/strings\""),
            Triple(IDENT_RELS, "Target=\"xl/workbook.xml\"", "TargetMode=\"External\" Target=\"https://example.invalid/book\""),
            Triple(IDENT_TYPES, "spreadsheetml.worksheet+xml", "spreadsheetml.chartsheet+xml"),
            Triple(IDENT_TYPES, "application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml", "application/vnd.ms-excel.macrosheet+xml"),
            Triple("xl/workbook.xml", "Muestra Dos", "muestra uno"),
            Triple("xl/workbook.xml", "Muestra Dos", "?"),
            Triple("xl/workbook.xml", "Muestra Dos", "&#x85;"),
            Triple("xl/workbook.xml", "date1904=\"true\"", "date1904=\"yes\""),
            Triple("xl/workbook.xml", "<sheets>", "<workbookPr/><sheets>"),
            Triple("xl/workbook.xml", "<sheets>", "<sheets/><sheets>"),
            Triple(FIRST_SHEET, SHEET_NS, "$SHEET_NS-other"),
            Triple(FIRST_SHEET, "<sheetData>", "<sheetData/><sheetData>"),
            Triple(FIRST_SHEET, "<sheetData>", "<container><sheetData>"),
            Triple(FIRST_SHEET, "<v>0</v>", "<v>350</v>"),
            Triple(STRING_TABLE, "<si><t/>", "<si><t/><t/>"),
        )
        for ((part, from, to) in mutators) {
            assertFailsWith<InvalidWorkbook>("$part: $to") { inspect(syntheticZip(inspectionParts().apply { replace(part, from, to) })) }
        }
        assertFailsWith<InvalidWorkbook> { inspect(syntheticZip(inspectionParts().apply { remove(STRING_TABLE) })) }
    }

    @Test fun `requires canonical increasing sparse positions and valid cell envelopes and tokens`() {
        for (cells in listOf(
            "<c/>", "<c r=\"A01\"/>", "<c r=\"a1\"/>", "<c r=\"XFE1\"/>", "<c r=\"A2\"/>",
            "<c r=\"B1\"/><c r=\"A1\"/>", "<c r=\"A1\"/><c r=\"A1\"/>", "<c r=\"A1\"><v/><v/></c>",
            "<c r=\"A1\"><f/><f/></c>", "<c r=\"A1\" t=\"inlineStr\"><is/><is/></c>",
            "<c r=\"A1\" t=\"b\"><v>true</v></c>", "<c r=\"A1\" t=\"e\"><v>#SPILL!</v></c>",
            "<c r=\"A1\" t=\"d\"><v>1900-02-29</v></c>", "<c r=\"A1\" t=\"d\"><v>2024-01-01T00:00:60Z</v></c>",
            "<c r=\"A1\"><v>NaN</v></c>", "<c r=\"A1\"><v>2,4</v></c>",
        )) assertFailsWith<InvalidWorkbook>(cells) { inspect(single("<row r=\"1\">$cells</row>")) }
        for (rows in listOf("<row/>", "<row r=\"0\"/>", "<row r=\"1048577\"/>", "<row r=\"2\"/><row r=\"1\"/>")) {
            assertFailsWith<InvalidWorkbook>(rows) { inspect(single(rows)) }
        }
        val valid = inspect(single("""<row r="1048576"><c r="XFD1048576" t="uninterpreted"><f/><v>ignored</v></c></row>""")).decodeToString()
        assertContains(valid, """{"a1":"XFD1048576","kind":"formula"}""")
        assertFalse("uninterpreted" in valid || "ignored" in valid)
    }

    @Test fun `required attributes cannot be supplied by a foreign namespace`() {
        val required = listOf(
            Triple(IDENT_TYPES, "PartName=", "PartName"),
            Triple(IDENT_TYPES, "ContentType=", "ContentType"),
            Triple(IDENT_RELS, "Id=", "Id"),
            Triple(IDENT_RELS, "Type=", "Type"),
            Triple(IDENT_RELS, "Target=", "Target"),
            Triple(WORKBOOK_LINKS, "Id=", "Id"),
            Triple(WORKBOOK_LINKS, "Type=", "Type"),
            Triple(WORKBOOK_LINKS, "Target=", "Target"),
            Triple("xl/workbook.xml", "name=", "name"),
            Triple("xl/workbook.xml", "r:id=", "id"),
            Triple(FIRST_SHEET, "<row r=", "r"),
            Triple(FIRST_SHEET, "<c r=", "r"),
        )
        for ((part, from, attr) in required) {
            val parts = inspectionParts().apply {
                val original = getValue(part).decodeToString()
                val prefix = from.substringBeforeLast(' ', "").let { if (it.isEmpty()) "" else "$it " }
                assertContains(original, from)
                put(part, original.replaceFirst(from, "${prefix}sample:$attr=")
                    .replaceFirst(" xmlns=", " xmlns:sample=\"urn:sample:attributes\" xmlns=").toByteArray())
            }
            val workbook = syntheticZip(parts)
            assertFailsWith<InvalidWorkbook>("$part $attr") { inspect(workbook) }
            if (part == IDENT_TYPES || part == IDENT_RELS) {
                // The deployed receipt envelope has a separate, unchanged identification profile.
                TeachWorkbookValidator().validate(workbook, workbook.size) {}
            }
        }
        val foreignExtension = inspectionParts().apply {
            replace(IDENT_TYPES, "</Types>", """<Default xmlns:sample="urn:sample:attributes" sample:Extension="txt" ContentType="text/plain"/></Types>""")
        }
        assertFailsWith<InvalidWorkbook> { inspect(syntheticZip(foreignExtension)) }
    }

    @Test fun `canonical attribute values and defaults are unchanged by foreign homonyms`() {
        val base = inspectionParts()
        val decorated = inspectionParts().apply {
            fun foreign(part: String, from: String, attrs: String) = replace(part, from,
                from.substringBeforeLast(' ') + " xmlns:sample=\"urn:sample:attributes\" $attrs " + from.substringAfterLast(' '))
            foreign(IDENT_TYPES, "<Override PartName=\"/xl/workbook.xml\"", """sample:PartName="/unused.xml" sample:ContentType="application/xml"""")
            foreign(IDENT_RELS, "<Relationship Id=\"rId1\"", """sample:Id="unused" sample:Type="urn:sample:unused" sample:Target="unused.xml" sample:TargetMode="Unused"""")
            foreign(WORKBOOK_LINKS, "<Relationship Id=\"first\"", """sample:Id="unused" sample:Type="urn:sample:unused" sample:Target="unused.xml" sample:TargetMode="Unused"""")
            foreign(WORKBOOK_LINKS, "<Relationship Id=\"second\"", """sample:TargetMode="Unused"""")
            foreign("xl/workbook.xml", "<workbookPr date1904=\"true\"", """sample:date1904="false"""")
            foreign("xl/workbook.xml", "<sheet name=\"Muestra", """sample:name="Unused" sample:state="Unused" sample:id="unused"""")
            foreign(FIRST_SHEET, "<row r=\"3\"", """sample:r="4"""")
            foreign(FIRST_SHEET, "<c r=\"A3\"", """sample:r="B3" sample:t="str"""")
            foreign(FIRST_SHEET, "<c r=\"AA2\"", """sample:t="b"""")
        }
        fun result(parts: Map<String, ByteArray>) = Json.parseToJsonElement(inspect(syntheticZip(parts)).decodeToString())
            .jsonObject.getValue("inspection")
        assertEquals(result(base), result(decorated))

        val noDateSetting = inspectionParts().apply { replace("xl/workbook.xml", "<workbookPr date1904=\"true\"/>", "<workbookPr/>") }
        decorated.replace("xl/workbook.xml", "sample:date1904=\"false\" date1904=\"true\"", "sample:date1904=\"true\"")
        assertEquals(result(noDateSetting), result(decorated))
    }

    @Test fun `strict XML applies to identification and ignored content but permits BOM UTF8 and escaped characters`() {
        val good = inspectionParts().apply {
            for (name in listOf(IDENT_RELS, FIRST_SHEET)) put(name, ("\uFEFF<?xml version=\"1.0\" encoding=\"UTF-8\"?>" + getValue(name).decodeToString()).toByteArray())
        }
        inspect(syntheticZip(good))
        val prefixes = listOf("<!DOCTYPE Types>", "<?ignored notice?>", "<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?>")
        for (prefix in prefixes) {
            assertFailsWith<InvalidWorkbook> { inspect(syntheticZip(inspectionParts().apply {
                put(IDENT_TYPES, prefix.toByteArray() + getValue(IDENT_TYPES))
            })) }
        }
        for (extra in listOf("<ext xml:base=\"local.xml\"/>", "<ext><?ignore notice?></ext>",
            "<ext><include xmlns=\"http://www.w3.org/2001/XInclude\"/></ext>", "<ext>&unknown;</ext>")) {
            assertFailsWith<InvalidWorkbook> { inspect(syntheticZip(inspectionParts().apply {
                replace(FIRST_SHEET, "</worksheet>", "$extra</worksheet>")
            })) }
        }
        assertFailsWith<InvalidWorkbook> { inspect(syntheticZip(inspectionParts().apply {
            put(IDENT_TYPES, getValue(IDENT_TYPES) + byteArrayOf(0xc3.toByte()))
        })) }
    }

    @Test fun `rejects corrupt opaque bytes even after the whole result model has been built`() {
        val marker = "ordinary unique opaque bytes".toByteArray()
        val workbook = syntheticZip(inspectionParts().apply { put("opaque.txt", marker) })
        val offset = (0..workbook.size - marker.size).first { workbook.copyOfRange(it, it + marker.size).contentEquals(marker) }
        workbook[offset] = 'O'.code.toByte()
        assertFailsWith<InvalidWorkbook> { inspect(workbook) }
    }

    @Test fun `inclusive selected part and total byte budgets include metadata and exclude opaque XML`() {
        val parts = inspectionParts()
        val xml = parts.filterKeys { it != "xl/tables/_rels/first.xml.rels" }.values
        val workbook = syntheticZip(parts, deflated = true)
        val limits = TeachWorkbookInspectionLimits(xmlPart = xml.maxOf(ByteArray::size), xmlTotal = xml.sumOf(ByteArray::size))
        inspect(workbook, limits)
        assertFailsWith<WorkbookBudgetExceeded> { inspect(workbook, limits.copy(xmlPart = limits.xmlPart - 1)) }
        assertFailsWith<WorkbookBudgetExceeded> { inspect(workbook, limits.copy(xmlTotal = limits.xmlTotal - 1)) }
        val exact = 256 * 1024
        val sheet = withinXml(worksheet(""), exact)
        inspect(syntheticZip(parts.apply { put(FIRST_SHEET, sheet) }))
        assertFailsWith<WorkbookBudgetExceeded> { inspect(syntheticZip(parts.apply { put(FIRST_SHEET, sheet + ' '.code.toByte()) })) }
    }

    @Test fun `counts limits in the JDK parser including opaque events and combined attributes`() {
        fun verify(xml: String, limits: TeachWorkbookInspectionLimits) {
            val parser = TeachWorkbookInspectionXml(limits, {}, { _, _ -> xml.toByteArray() })
            parser.parse("synthetic", "urn:sample", "r") { skip() }
        }
        val exact = TeachWorkbookInspectionLimits(events = 7, depth = 2, attributes = 2)
        val doc = "<r xmlns='urn:sample' a='1'><ignored/><!-- counted --></r>"
        verify(doc, exact)
        assertFailsWith<WorkbookBudgetExceeded> { verify(doc, exact.copy(events = 6)) }
        assertFailsWith<WorkbookBudgetExceeded> { verify(doc, exact.copy(depth = 1)) }
        assertFailsWith<WorkbookBudgetExceeded> { verify(doc, exact.copy(attributes = 1)) }
        assertFailsWith<WorkbookBudgetExceeded> { verify(doc.replace("<ignored/>", "<ignored a='1' b='2' c='3'/>"), exact) }
        val depth = "<r xmlns='urn:sample'>" + "<x>".repeat(15) + "</x>".repeat(15) + "</r>"
        verify(depth, TeachWorkbookInspectionLimits())
        assertFailsWith<WorkbookBudgetExceeded> { verify(depth.replaceFirst("</x>", "<x/></x>"), TeachWorkbookInspectionLimits()) }
    }

    @Test fun `inclusive model counts and escaped UTF8 response bytes fail without truncation`() {
        val normal = syntheticZip(inspectionParts())
        val limits = TeachWorkbookInspectionLimits(sheets = 2, strings = 3, rows = 3, cells = 19)
        inspect(normal, limits)
        for (narrow in listOf(limits.copy(sheets = 1), limits.copy(strings = 2), limits.copy(rows = 2), limits.copy(cells = 18))) {
            assertFailsWith<WorkbookBudgetExceeded> { inspect(normal, narrow) }
        }
        val bytes = single("""<row r="1"><c r="A1" t="inlineStr"><is><r><t>${"😀".repeat(255)}</t></r><r><t>&quot;&#10;é</t></r></is></c></row>""")
        val encoded = inspect(bytes)
        assertContains(encoded.decodeToString(), "\\\"\\né")
        assertContentEquals(encoded, inspect(bytes, TeachWorkbookInspectionLimits(response = encoded.size)))
        assertFailsWith<WorkbookBudgetExceeded> { inspect(bytes, TeachWorkbookInspectionLimits(response = encoded.size - 1)) }
        assertFailsWith<WorkbookBudgetExceeded> { inspect(bytes, TeachWorkbookInspectionLimits(text = 1023)) }
        for (length in 64..65) {
            val number = single("""<row r="1"><c r="A1"><v>${"9".repeat(length)}</v></c></row>""")
            if (length == 64) inspect(number) else assertFailsWith<WorkbookBudgetExceeded> { inspect(number) }
        }
    }

    @Test fun `default response budget includes the last encoded byte without truncation`() {
        val escapedWords = (1..62).joinToString("") { row ->
            """<row r="$row"><c r="A$row" t="str"><v>${"é".repeat(500)}</v></c></row>"""
        }
        fun workbook(tail: String) = single(escapedWords + """<row r="63"><c r="A63" t="str"><v>$tail</v></c></row>""")
        val remaining = 65_536 - inspect(workbook(""), TeachWorkbookInspectionLimits(response = 128 * 1024)).size
        assertTrue(remaining in 1..1_022)
        val tail = "&quot;" + "a".repeat(remaining - 2)
        val exact = inspect(workbook(tail))
        assertEquals(65_536, exact.size)
        assertTrue(exact.decodeToString().endsWith("]}]}}"))
        assertFailsWith<WorkbookBudgetExceeded> { inspect(workbook(tail + "a")) }
    }

    private fun single(rows: String) = syntheticZip(inspectionParts().apply { put(FIRST_SHEET, worksheet(rows).toByteArray()) })

    private fun withinXml(doc: String, bytes: Int) = (doc + " ".repeat(bytes - doc.toByteArray().size)).toByteArray()
}
