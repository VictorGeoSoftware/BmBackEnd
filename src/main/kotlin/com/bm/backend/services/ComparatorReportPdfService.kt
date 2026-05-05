package com.bm.backend.services

import com.bm.backend.models.ComparatorReportColumn
import com.bm.backend.models.ComparatorReportPdfRequest
import com.bm.backend.models.ComparatorReportProposal
import com.lowagie.text.Document
import com.lowagie.text.Element
import com.lowagie.text.Font
import com.lowagie.text.Image
import com.lowagie.text.PageSize
import com.lowagie.text.Paragraph
import com.lowagie.text.Phrase
import com.lowagie.text.Rectangle
import com.lowagie.text.pdf.PdfPCell
import com.lowagie.text.pdf.PdfPTable
import com.lowagie.text.pdf.PdfWriter
import java.io.ByteArrayOutputStream
import java.awt.Color
import java.util.Locale

class ComparatorReportPdfService {

    fun generate(request: ComparatorReportPdfRequest): ByteArray {
        val outputStream = ByteArrayOutputStream()
        val document = Document(PageSize.A4.rotate(), 14f, 14f, 10f, 10f)
        PdfWriter.getInstance(document, outputStream)

        document.open()
        addTitle(document, request)
        addSupplyData(document, request)
        addComparisonTable(document, request)
        addDisclaimer(document)
        document.close()

        return outputStream.toByteArray()
    }

    private fun addTitle(document: Document, request: ComparatorReportPdfRequest) {
        val titleTable = PdfPTable(floatArrayOf(4.4f, 1.6f)).apply {
            widthPercentage = 100f
            setWidths(floatArrayOf(4.4f, 1.6f))
            setSpacingAfter(12f)
        }
        titleTable.addCell(
            buildCell(
                text = request.title,
                background = COLOR_HIGHLIGHT,
                align = Element.ALIGN_LEFT,
                padding = 5f,
                font = FONT_TITLE
            )
        )

        val logoCell = PdfPCell().apply {
            border = Rectangle.NO_BORDER
            horizontalAlignment = Element.ALIGN_RIGHT
            verticalAlignment = Element.ALIGN_MIDDLE
            setPadding(0f)
        }
        loadLogoImage()?.let { logo ->
            logoCell.addElement(logo)
        }
        titleTable.addCell(logoCell)

        document.add(titleTable)
    }

    private fun addSupplyData(document: Document, request: ComparatorReportPdfRequest) {
        val infoTable = PdfPTable(floatArrayOf(1.2f, 3.8f)).apply {
            widthPercentage = 74f
            horizontalAlignment = Element.ALIGN_LEFT
            setSpacingAfter(14f)
        }

        infoTable.addCell(
            buildCell(
                ComparatorReportPdfTexts.SUPPLY_HOLDER_LABEL,
                font = FONT_LABEL,
                border = Rectangle.NO_BORDER
            )
        )
        infoTable.addCell(buildCell(request.supplyHolder, font = FONT_VALUE, border = Rectangle.NO_BORDER))

        infoTable.addCell(
            buildCell(
                ComparatorReportPdfTexts.SUPPLY_ADDRESS_LABEL,
                font = FONT_LABEL,
                border = Rectangle.NO_BORDER
            )
        )
        infoTable.addCell(buildCell(request.supplyAddress, font = FONT_VALUE, border = Rectangle.NO_BORDER))

        infoTable.addCell(buildCell(ComparatorReportPdfTexts.CUPS_LABEL, font = FONT_LABEL, border = Rectangle.NO_BORDER))
        infoTable.addCell(buildCell(request.cups, font = FONT_VALUE, border = Rectangle.NO_BORDER))

        document.add(infoTable)
    }

    private fun addComparisonTable(document: Document, request: ComparatorReportPdfRequest) {
        val columns = mutableListOf<ComparatorReportColumn>()
        columns.add(request.customerConditions)
        columns.addAll(request.proposals.map { proposal ->
            ComparatorReportColumn(
                title = proposal.title,
                powerTermItems = proposal.powerTermItems,
                annualPowerTermCost = proposal.annualPowerTermCost,
                consumedEnergyItems = proposal.consumedEnergyItems,
                annualEnergyCost = proposal.annualEnergyCost,
                extraServices = proposal.extraServices,
                electricalTax = proposal.electricalTax,
                iva = proposal.iva,
                totalAnnualPrice = proposal.totalAnnualPrice
            )
        })

        val table = PdfPTable(2 + columns.size).apply {
            widthPercentage = 100f
            keepTogether = true
            isSplitLate = false
            setSpacingAfter(12f)
            val widths = MutableList(2 + columns.size) { 1f }
            widths[0] = 1.7f
            widths[1] = 1.15f
            if (columns.isNotEmpty()) {
                widths[2] = 1.25f
            }
            setWidths(widths.toFloatArray())
        }

        table.addCell(buildHeaderCell(request.tariffName, COLOR_HIGHLIGHT))
        table.addCell(buildHeaderCell(ComparatorReportPdfTexts.ANNUAL_CONSUMPTION_COLUMN, COLOR_HIGHLIGHT))
        columns.forEachIndexed { index, column ->
            val columnTitle = if (index == 0) {
                ComparatorReportPdfTexts.CURRENT_CONDITIONS_COLUMN
            } else {
                column.title
            }
            val background = if (index == 0) COLOR_SECONDARY_HEADER else Color.WHITE
            table.addCell(buildHeaderCell(columnTitle, background))
        }

        addSeriesRows(
            table = table,
            sideTitle = ComparatorReportPdfTexts.POWER_TERM_SIDE_TITLE,
            periods = request.powerTermRows.map { it.period },
            consumptionValues = request.powerTermRows.map { formatNumber(it.value) },
            columnValues = columns.map { column ->
                request.powerTermRows.indices.map { idx ->
                    formatNumber(column.powerTermItems.getOrElse(idx) { 0.0 })
                }
            }
        )

        addTotalRow(
            table,
            ComparatorReportPdfTexts.POWER_TERM_ANNUAL_COST_LABEL,
            columns.map { it.annualPowerTermCost }
        )

        addSeriesRows(
            table = table,
            sideTitle = ComparatorReportPdfTexts.ENERGY_CONSUMED_SIDE_TITLE,
            periods = request.energyConsumedRows.map { it.period },
            consumptionValues = request.energyConsumedRows.map { "${it.value} kWh" },
            columnValues = columns.map { column ->
                request.energyConsumedRows.indices.map { idx ->
                    formatNumber(column.consumedEnergyItems.getOrElse(idx) { 0.0 })
                }
            }
        )

        addTotalRow(
            table,
            ComparatorReportPdfTexts.ENERGY_TERM_ANNUAL_COST_LABEL,
            columns.map { it.annualEnergyCost }
        )

        addMetaRow(table, ComparatorReportPdfTexts.EXTRA_SERVICES_LABEL, columns.map { it.extraServices })
        addMetaRow(table, buildLabelWithReferenceValue(ComparatorReportPdfTexts.ELECTRIC_TAX_LABEL, request.impuestoElectrico), columns.map { it.electricalTax })
        addMetaRow(table, buildLabelWithReferenceValue(ComparatorReportPdfTexts.IVA_LABEL, request.iva), columns.map { it.iva })

        addTotalRow(
            table,
            ComparatorReportPdfTexts.ANNUAL_INVOICE_COST_LABEL,
            columns.map { it.totalAnnualPrice },
            emphasize = true
        )

        addSavingsRow(table, request.proposals)

        document.add(table)
    }

    private fun addSeriesRows(
        table: PdfPTable,
        sideTitle: String,
        periods: List<String>,
        consumptionValues: List<String>,
        columnValues: List<List<String>>
    ) {
        periods.forEachIndexed { index, period ->
            if (index == 0) {
                table.addCell(
                    buildCell(
                        text = sideTitle,
                        rowSpan = periods.size,
                        align = Element.ALIGN_CENTER,
                        valign = Element.ALIGN_MIDDLE,
                        font = FONT_LABEL,
                        padding = 2f
                    )
                )
            }
            table.addCell(buildCell(text = "$period ${consumptionValues[index]}", align = Element.ALIGN_CENTER, font = FONT_VALUE))
            columnValues.forEach { values ->
                table.addCell(buildCell(text = values.getOrElse(index) { "-" }, align = Element.ALIGN_CENTER, font = FONT_VALUE))
            }
        }
    }

    private fun addTotalRow(table: PdfPTable, label: String, values: List<String>, emphasize: Boolean = false) {
        val labelBackground = if (emphasize) COLOR_HIGHLIGHT else COLOR_HIGHLIGHT
        val valueBackground = if (emphasize) COLOR_HIGHLIGHT else COLOR_HIGHLIGHT
        table.addCell(
            buildCell(
                text = label,
                colSpan = 2,
                align = Element.ALIGN_CENTER,
                background = labelBackground,
                font = FONT_SECTION
            )
        )
        values.forEach { value ->
            table.addCell(
                buildCell(
                    text = withCurrency(value),
                    align = Element.ALIGN_CENTER,
                    background = valueBackground,
                    font = FONT_SECTION
                )
            )
        }
    }

    private fun addMetaRow(table: PdfPTable, label: String, values: List<String>) {
        table.addCell(buildCell(text = label, colSpan = 2, align = Element.ALIGN_CENTER, font = FONT_LABEL))
        values.forEach { value ->
            table.addCell(buildCell(text = value, align = Element.ALIGN_CENTER, font = FONT_VALUE))
        }
    }

    private fun addSavingsRow(table: PdfPTable, proposals: List<ComparatorReportProposal>) {
        val totalColumns = table.numberOfColumns
        val fixedColumns = 3
        val firstColumnsBeforeLabel = 2

        table.addCell(buildCell("", colSpan = firstColumnsBeforeLabel, border = Rectangle.NO_BORDER))
        table.addCell(
            buildCell(
                text = ComparatorReportPdfTexts.ANNUAL_SAVINGS_LABEL,
                background = COLOR_SAVINGS,
                align = Element.ALIGN_CENTER,
                font = FONT_SECTION
            )
        )

        val proposalCells = totalColumns - fixedColumns
        repeat(proposalCells) { index ->
            val proposal = proposals.getOrNull(index)
            val text = if (proposal == null) {
                ""
            } else {
                buildSavingsText(proposal)
            }
            table.addCell(
                buildCell(
                    text = text,
                    background = COLOR_SAVINGS,
                    align = Element.ALIGN_CENTER,
                    font = FONT_SECTION
                )
            )
        }
    }

    private fun buildSavingsText(proposal: ComparatorReportProposal): String {
        val difference = proposal.annualPriceDifference.orEmpty()
        val percentage = proposal.annualSavingsPercentage?.let { "$it%" }.orEmpty()
        return listOf(difference, percentage).filter { it.isNotBlank() }.joinToString("\n")
    }

    private fun addDisclaimer(document: Document) {
        document.add(
            Paragraph(ComparatorReportPdfTexts.DATA_PROTECTION_DISCLAIMER, FONT_FOOTER).apply {
                spacingBefore = 8f
                alignment = Element.ALIGN_JUSTIFIED
            }
        )
    }

    private fun buildLabelWithReferenceValue(label: String, referenceValue: String): String {
        if (referenceValue.isBlank() || referenceValue == "-") return label
        return "$label - $referenceValue"
    }

    private fun buildHeaderCell(text: String, background: Color): PdfPCell {
        return buildCell(
            text = text,
            background = background,
            align = Element.ALIGN_CENTER,
            valign = Element.ALIGN_MIDDLE,
            font = FONT_SECTION,
            padding = 3f
        )
    }

    private fun buildCell(
        text: String,
        background: Color = Color.WHITE,
        align: Int = Element.ALIGN_LEFT,
        valign: Int = Element.ALIGN_MIDDLE,
        colSpan: Int = 1,
        rowSpan: Int = 1,
        border: Int = Rectangle.BOX,
        font: Font = FONT_VALUE,
        padding: Float = 2f
    ): PdfPCell {
        return PdfPCell(Phrase(text, font)).apply {
            horizontalAlignment = align
            verticalAlignment = valign
            this.backgroundColor = background
            this.colspan = colSpan
            this.rowspan = rowSpan
            this.border = border
            setPadding(padding)
        }
    }

    private fun formatNumber(value: Double): String = String.format(Locale.US, "%.6f", value)

    private fun withCurrency(value: String): String {
        if (value.isBlank() || value == "-") return value
        return if (value.endsWith("€") || value.endsWith("%")) value else "$value €"
    }

    private fun loadLogoImage(): Image? {
        val resourceBytes = this::class.java.classLoader
            ?.getResourceAsStream(LOGO_RESOURCE_PATH)
            ?.use { it.readBytes() }
            ?: return null

        return Image.getInstance(resourceBytes).apply {
            scaleToFit(135f, 42f)
            alignment = Element.ALIGN_RIGHT
        }
    }

    private companion object {
        const val LOGO_RESOURCE_PATH = "images/logo_briel.png"
        val COLOR_HIGHLIGHT = Color(246, 222, 0)
        val COLOR_SECONDARY_HEADER = Color(178, 208, 230)
        val COLOR_SAVINGS = Color(171, 231, 179)

        val FONT_TITLE = Font(Font.HELVETICA, 12.5f, Font.BOLD)
        val FONT_SECTION = Font(Font.HELVETICA, 8f, Font.BOLD)
        val FONT_LABEL = Font(Font.HELVETICA, 7f, Font.BOLD)
        val FONT_VALUE = Font(Font.HELVETICA, 7f, Font.NORMAL)
        val FONT_FOOTER = Font(Font.HELVETICA, 6f, Font.NORMAL)
    }
}
