package com.bm.backend.testing

import com.bm.backend.models.TeachDraftPreviewRow
import com.bm.backend.services.DOC_RELS_NS
import java.util.zip.ZipInputStream

internal fun importWorkbook(): ByteArray = checkNotNull(object {}.javaClass.getResourceAsStream(
    "/ensename/gesternova-20td-import-synthetic.xlsx")).use { it.readBytes() }

internal fun importParts(bytes: ByteArray = importWorkbook()): MutableMap<String, ByteArray> = linkedMapOf<String, ByteArray>().apply {
    ZipInputStream(bytes.inputStream()).use { zip ->
        while (true) put(zip.nextEntry?.name ?: break, zip.readBytes())
    }
}

internal fun MutableMap<String, ByteArray>.edit(part: String, edit: (String) -> String) {
    val before = getValue(part).decodeToString()
    put(part, edit(before).also { check(it != before) }.encodeToByteArray())
}

internal fun editImportWorkbook(change: (String) -> String) = syntheticZip(importParts().apply { edit(PREVIEW_SHEET_PART, change) })

internal fun markedImportWorkbook(rows: String = markerRow(1, "gesternova")) = syntheticZip(importParts().apply {
    edit("xl/workbook.xml") { it.replace("</sheets>", """<sheet xmlns:r="$DOC_RELS_NS" name="BM Identity" sheetId="2" state="visible" r:id="identity"/></sheets>""") }
    edit("xl/_rels/workbook.xml.rels") { it.replace("</Relationships>", """<Relationship Type="$DOC_RELS_NS/worksheet" Target="worksheets/sheet2.xml" Id="identity"/></Relationships>""") }
    edit("[Content_Types].xml") { it.replace("</Types>", """<Override PartName="/xl/worksheets/sheet2.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/></Types>""") }
    put("xl/worksheets/sheet2.xml", """<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>$rows</sheetData></worksheet>""".encodeToByteArray())
})

internal fun incompatibleImportWorkbook() = editImportWorkbook {
    it.replace("""r="T18" t="n"><v>0.02609</v>""", """r="T18" t="inlineStr"><is><t>0.02609</t></is>""")
}

/** Values transcribed from the independently authored oraculo-importacion.md. */
internal fun importOracleRows(): List<TeachDraftPreviewRow> = previewOracleRows().zip(listOf(
    listOf("0.151234567891", "0.08123", "0.02956"),
    listOf("0.13234", "0.07432", "0.02734"),
    listOf("0", "0.07987", "0.02609"),
)).map { (row, decimals) -> row.copy(prices = row.prices.zip(decimals) { price, decimal -> price.copy(decimal = decimal) }) }
