package com.bm.backend.services.premiumreport

import com.lowagie.text.Document
import com.lowagie.text.Image
import com.lowagie.text.pdf.PdfPageEventHelper
import com.lowagie.text.pdf.PdfWriter

/**
 * PowerApp branding on every page (feedback 2026-11-03): a faint bolt+€ watermark centred under the content on
 * all pages, cover included, and a small PowerApp header logo in the top margin from page 2 on (the cover shows
 * the large logo itself). Portrait and landscape pages are both handled. Create one instance per render.
 */
class PremiumPageDecorator(
    private val watermarkResource: String = WATERMARK_RESOURCE,
    private val logoResource: String = PremiumCoverSection.LOGO_RESOURCE
) : PdfPageEventHelper() {
    private var watermark: Image? = null
    private var logo: Image? = null

    override fun onOpenDocument(writer: PdfWriter, document: Document) {
        watermark = load(watermarkResource)
        logo = load(logoResource)
    }

    override fun onEndPage(writer: PdfWriter, document: Document) {
        val page = document.pageSize
        watermark?.let { image ->
            val side = minOf(page.width, page.height) * WATERMARK_FRACTION
            image.scaleToFit(side, side)
            image.setAbsolutePosition(
                (page.width - image.scaledWidth) / 2f,
                (page.height - image.scaledHeight) / 2f
            )
            writer.directContentUnder.addImage(image)
        }
        if (writer.pageNumber > 1) logo?.let { image ->
            image.scaleToFit(page.width, HEADER_LOGO_HEIGHT)
            image.setAbsolutePosition(document.left(), page.height - HEADER_LOGO_TOP - image.scaledHeight)
            writer.directContentUnder.addImage(image)
        }
    }

    private fun load(resource: String): Image {
        val bytes = PremiumPageDecorator::class.java.getResourceAsStream(resource)?.use { it.readBytes() }
            ?: throw IllegalStateException("Missing report branding resource $resource")
        return Image.getInstance(bytes)
    }

    companion object {
        const val WATERMARK_RESOURCE = "/images/powerapp_watermark.png"
        private const val WATERMARK_FRACTION = 0.6f
        /** Centred vertically inside the top margin, above any section content. */
        private const val HEADER_LOGO_HEIGHT = 18f
        private const val HEADER_LOGO_TOP = 20f
    }
}
