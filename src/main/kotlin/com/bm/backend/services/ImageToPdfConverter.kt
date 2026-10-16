package com.bm.backend.services

import com.lowagie.text.Document
import com.lowagie.text.Image
import com.lowagie.text.Rectangle
import com.lowagie.text.pdf.PdfWriter
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO

/**
 * Wraps a single JPEG/PNG bill photo into a one-page PDF so the existing
 * Docling `/extract-all` (PDF-only, with OCR) can process it.
 * The page size equals the (possibly downscaled) image size, so nothing is cropped.
 */
class ImageToPdfConverter(private val maxLongEdgePx: Int = DEFAULT_MAX_LONG_EDGE_PX) {

    fun convert(image: File): File {
        val bytes = image.readBytes()
        if (!hasImageMagicBytes(bytes)) throw InvalidImageException()
        val pdfImage = try {
            Image.getInstance(downscaleIfNeeded(bytes))
        } catch (e: Exception) {
            throw InvalidImageException(cause = e)
        }
        val width = pdfImage.plainWidth
        val height = pdfImage.plainHeight
        if (width <= 0f || height <= 0f) throw InvalidImageException()

        val output = Files.createTempFile("consumption_photo_", ".pdf").toFile()
        try {
            val document = Document(Rectangle(width, height), 0f, 0f, 0f, 0f)
            output.outputStream().use { out ->
                PdfWriter.getInstance(document, out)
                document.open()
                pdfImage.setAbsolutePosition(0f, 0f)
                pdfImage.scaleAbsolute(width, height)
                document.add(pdfImage)
                document.close()
            }
            return output
        } catch (e: Exception) {
            output.delete()
            throw e
        }
    }

    private fun downscaleIfNeeded(bytes: ByteArray): ByteArray {
        val source = ImageIO.read(bytes.inputStream()) ?: throw InvalidImageException()
        val longEdge = maxOf(source.width, source.height)
        if (longEdge <= maxLongEdgePx) return bytes
        val scale = maxLongEdgePx.toDouble() / longEdge
        val w = (source.width * scale).toInt().coerceAtLeast(1)
        val h = (source.height * scale).toInt().coerceAtLeast(1)
        val scaled = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = scaled.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.drawImage(source, 0, 0, w, h, null)
        g.dispose()
        return ByteArrayOutputStream().also { ImageIO.write(scaled, "jpg", it) }.toByteArray()
    }

    companion object {
        const val DEFAULT_MAX_LONG_EDGE_PX = 2000

        private val JPEG_MAGIC = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
        private val PNG_MAGIC = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)

        fun hasImageMagicBytes(bytes: ByteArray): Boolean =
            startsWith(bytes, JPEG_MAGIC) || startsWith(bytes, PNG_MAGIC)

        fun hasImageMagicBytes(file: File): Boolean =
            file.inputStream().use { hasImageMagicBytes(it.readNBytes(4)) }

        private fun startsWith(bytes: ByteArray, prefix: ByteArray): Boolean =
            bytes.size >= prefix.size && prefix.indices.all { bytes[it] == prefix[it] }
    }
}
