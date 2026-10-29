package com.bm.backend.services.premiumreport

import com.bm.backend.models.ReportDataset
import com.bm.backend.models.StudySnapshotFields
import com.bm.backend.models.StudySnapshotStatus
import com.lowagie.text.Document
import com.lowagie.text.Element
import com.lowagie.text.Image
import com.lowagie.text.Paragraph
import com.lowagie.text.pdf.PdfWriter
import java.time.Clock

/**
 * Portrait cover: BM logo, approved title/subtitle, issue date and the supply identity. Holder/address come from
 * the broker-entered comparison, CUPS from the study; snapshot rows appear only when published (never placeholders).
 */
class PremiumCoverSection(
    private val clock: Clock,
    private val logoResource: String = LOGO_RESOURCE
) : PremiumReportSection {

    override fun write(document: Document, writer: PdfWriter, dataset: ReportDataset) {
        val logo = loadLogo()
        document.setPageSize(PremiumReportTheme.PAGE_PORTRAIT)
        document.newPage()

        logo.scaleToFit(LOGO_MAX_WIDTH, LOGO_MAX_HEIGHT)
        logo.alignment = Element.ALIGN_CENTER
        document.add(Paragraph(" ", PremiumReportTheme.body()).apply { spacingAfter = 30f })
        document.add(logo)
        document.add(centered(PremiumReportTexts.TITLE, PremiumReportTheme.bold(PremiumReportTheme.SIZE_TITLE), 40f, 8f))
        document.add(centered(PremiumReportTexts.SUBTITLE, PremiumReportTheme.regular(PremiumReportTheme.SIZE_SUBTITLE, PremiumReportTheme.COLOR_MUTED), 0f, 18f))
        document.add(
            centered(
                "${PremiumReportTexts.ISSUE_DATE_LABEL} ${PremiumReportFormat.issueDate(clock)}",
                PremiumReportTheme.regular(color = PremiumReportTheme.COLOR_MUTED), 0f, 60f
            )
        )

        document.add(PremiumReportBlocks.sectionBar(PremiumReportTexts.SUPPLY_SECTION))
        val fields = dataset.snapshot.fields?.takeIf { dataset.snapshot.status == StudySnapshotStatus.AVAILABLE }
        document.add(PremiumReportBlocks.labelValueTable(identityRows(dataset) + snapshotRows(fields)))
        if (fields == null) {
            document.add(Paragraph(PremiumReportTexts.SNAPSHOT_UNAVAILABLE, PremiumReportTheme.regular(color = PremiumReportTheme.COLOR_MUTED)).apply {
                spacingBefore = 10f
            })
        }
    }

    private fun identityRows(dataset: ReportDataset): List<Pair<String, String>> = listOf(
        PremiumReportTexts.HOLDER_LABEL to PremiumReportFormat.uppercase(dataset.comparison.supplyHolder),
        PremiumReportTexts.ADDRESS_LABEL to PremiumReportFormat.uppercase(dataset.comparison.supplyAddress),
        PremiumReportTexts.CUPS_LABEL to dataset.cups,
        PremiumReportTexts.TARIFF_LABEL to dataset.comparison.tariffName
    )

    private fun snapshotRows(fields: StudySnapshotFields?): List<Pair<String, String>> {
        if (fields == null) return emptyList()
        return listOfNotNull(
            present(fields.distributorName)?.let { PremiumReportTexts.DISTRIBUTOR_LABEL to it },
            location(fields)?.let { PremiumReportTexts.LOCATION_LABEL to it },
            fields.contractedPowerKw?.let(PremiumReportFormat::periodPowers)?.let { PremiumReportTexts.CONTRACTED_POWER_LABEL to it },
            present(fields.lastReadingDate)?.let { PremiumReportTexts.LAST_READING_LABEL to PremiumReportFormat.isoDate(it) }
        )
    }

    /** `<municipality> (<province>) — CP <postalCode>`, dropping absent parts. */
    private fun location(fields: StudySnapshotFields): String? {
        val municipality = present(fields.municipality)
        val province = present(fields.province)
        val postalCode = present(fields.postalCode)
        val place = when {
            municipality != null && province != null -> "$municipality ($province)"
            else -> municipality ?: province
        }
        val code = postalCode?.let { "CP $it" }
        return listOfNotNull(place, code).takeIf { it.isNotEmpty() }?.joinToString(" — ")
    }

    private fun present(value: String?): String? = value?.trim()?.takeIf { it.isNotEmpty() }

    private fun centered(text: String, font: com.lowagie.text.Font, before: Float, after: Float) =
        Paragraph(text, font).apply {
            alignment = Element.ALIGN_CENTER
            spacingBefore = before
            spacingAfter = after
        }

    private fun loadLogo(): Image {
        val bytes = PremiumCoverSection::class.java.getResourceAsStream(logoResource)?.use { it.readBytes() }
            ?: throw IllegalStateException("Missing report logo resource $logoResource")
        return Image.getInstance(bytes)
    }

    companion object {
        const val LOGO_RESOURCE = "/images/logo_briel.png"
        private const val LOGO_MAX_WIDTH = 240f
        private const val LOGO_MAX_HEIGHT = 141f
    }
}
