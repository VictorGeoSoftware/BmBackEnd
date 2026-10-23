package com.bm.backend.services

import com.lowagie.text.pdf.PdfReader
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class ImageToPdfConverterTest {
    private val converter = ImageToPdfConverter()

    private fun imageFile(format: String, w: Int, h: Int): File {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val f = Files.createTempFile("img_test_", ".$format").toFile()
        ImageIO.write(img, format, f)
        return f
    }

    private fun assertOnePagePdf(pdf: File, expectedRatio: Double) {
        val bytes = pdf.readBytes()
        assertEquals("%PDF", String(bytes, 0, 4))
        val reader = PdfReader(bytes)
        try {
            assertEquals(1, reader.numberOfPages)
            val page = reader.getPageSize(1)
            assertEquals(expectedRatio, (page.width / page.height).toDouble(), 0.01)
        } finally {
            reader.close()
        }
    }

    @Test
    fun `jpeg becomes one page pdf with same aspect ratio`() {
        val img = imageFile("jpg", 600, 900)
        val pdf = converter.convert(img)
        try {
            assertOnePagePdf(pdf, 600.0 / 900.0)
            val page = PdfReader(pdf.readBytes()).getPageSize(1)
            assertEquals(600f, page.width, 0.5f)
        } finally { img.delete(); pdf.delete() }
    }

    @Test
    fun `png becomes one page pdf with same aspect ratio`() {
        val img = imageFile("png", 800, 500)
        val pdf = converter.convert(img)
        try { assertOnePagePdf(pdf, 800.0 / 500.0) } finally { img.delete(); pdf.delete() }
    }

    @Test
    fun `large image is downscaled without cropping`() {
        val img = imageFile("jpg", 4032, 3024)
        val pdf = converter.convert(img)
        try {
            assertOnePagePdf(pdf, 4032.0 / 3024.0)
            val page = PdfReader(pdf.readBytes()).getPageSize(1)
            assertEquals(ImageToPdfConverter.DEFAULT_MAX_LONG_EDGE_PX.toFloat(), page.width, 1f)
        } finally { img.delete(); pdf.delete() }
    }

    @Test
    fun `text bytes are rejected as invalid image`() {
        val f = Files.createTempFile("fake_", ".jpg").toFile()
        f.writeText("this is not an image")
        try {
            assertFalse(ImageToPdfConverter.hasImageMagicBytes(f))
            assertFailsWith<InvalidImageException> { converter.convert(f) }
        } finally { f.delete() }
    }

    @Test
    fun `truncated jpeg with valid magic is rejected`() {
        val f = Files.createTempFile("trunc_", ".jpg").toFile()
        f.writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x00, 0x01))
        try {
            assertTrue(ImageToPdfConverter.hasImageMagicBytes(f))
            assertFailsWith<InvalidImageException> { converter.convert(f) }
        } finally { f.delete() }
    }
}
