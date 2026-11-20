package com.bm.backend.testing

import com.bm.backend.models.TeachClarificationCitation
import com.bm.backend.models.TeachDraftPreviewPrice
import com.bm.backend.models.TeachDraftPreviewRow
import java.util.zip.ZipInputStream

internal const val PREVIEW_FIXTURE_PROFILE = "gesternova-20td-sanitized"
internal const val PREVIEW_FIXTURE_HASH = "45c27cd6d6c646f182c2b1a4bd8f0863a21d5ffeef6c09df40f644d318cd85b6"
internal const val PREVIEW_FIXTURE_REVISION = "ee7d23208739560cff841d036cb0b80939d3b37dac8ee74fd4a179b8d3d7429f"
internal const val PREVIEW_SHEET_PART = "xl/worksheets/sheet1.xml"

internal fun previewWorkbook(): ByteArray = checkNotNull(object {}.javaClass.getResourceAsStream(
    "/ensename/gesternova-20td-sanitized.xlsx")).use { it.readBytes() }

internal fun editPreviewWorkbook(part: String = PREVIEW_SHEET_PART, edit: (String) -> String): ByteArray {
    val parts = linkedMapOf<String, ByteArray>()
    ZipInputStream(previewWorkbook().inputStream()).use { zip ->
        while (true) {
            val name = zip.nextEntry?.name ?: break
            val bytes = zip.readBytes()
            parts[name] = if (name == part) {
                val original = bytes.decodeToString()
                edit(original).also { check(it != original) }.encodeToByteArray()
            } else bytes
        }
    }
    check(part in parts)
    return syntheticZip(parts)
}

/** Values/citations transcribed from the independently approved oraculo-borrador.md. */
internal fun previewOracleRows(): List<TeachDraftPreviewRow> {
    fun cite(ref: String) = TeachClarificationCitation("Tarifas", ref)
    return listOf(
        listOf("Oferta sintética A", "0.12125", "0.07123", "0.02456"),
        listOf("Oferta sintética B", "0", "0.06432", "0.02234"),
        listOf("Oferta sintética C", "0.14321", "0.06987", "0.02109"),
    ).mapIndexed { index, (name, energy, power1, power2) ->
        val row = 16 + index
        TeachDraftPreviewRow(name, cite("I$row"), "2.0TD", cite("F$row"), "Precio por Periodos", cite("G$row"), listOf(
            TeachDraftPreviewPrice("energy", "P1", "€/kWh", energy, cite("K14"), cite("K$row")),
            TeachDraftPreviewPrice("power", "P1", "€/kWdia", power1, cite("S14"), cite("S$row")),
            TeachDraftPreviewPrice("power", "P2", "€/kWdia", power2, cite("T14"), cite("T$row")),
        ))
    }
}
