package com.bm.backend.testing

import com.lowagie.text.pdf.PdfArray
import com.lowagie.text.pdf.PdfDictionary
import com.lowagie.text.pdf.PdfName
import com.lowagie.text.pdf.PdfReader
import com.lowagie.text.pdf.parser.PdfTextExtractor

/** Read-only inspection of a rendered PDF for tests: pages, orientation, text, fonts and metadata. */
class PdfInspection(val bytes: ByteArray) {
    private val reader = PdfReader(bytes)

    val isPdf: Boolean get() = String(bytes.copyOfRange(0, 5), Charsets.US_ASCII) == "%PDF-"
    val pageCount: Int get() = reader.numberOfPages

    /** true for portrait pages (width < height), in page order. */
    val portraitPages: List<Boolean>
        get() = (1..pageCount).map { reader.getPageSizeWithRotation(it).let { r -> r.width < r.height } }

    /** Text of all pages with whitespace collapsed to single spaces. */
    val text: String by lazy {
        val extractor = PdfTextExtractor(reader)
        (1..pageCount).joinToString(" ") { extractor.getTextFromPage(it) }.replace('\u00A0', ' ').replace(Regex("\\s+"), " ").trim()
    }

    val info: Map<String, String>
        get() = reader.info.mapValues { it.value.toString() }

    /** Base font name -> embedded flag for every font referenced by any page. */
    val fonts: Map<String, Boolean>
        get() {
            val result = linkedMapOf<String, Boolean>()
            for (page in 1..pageCount) {
                val fontDict = reader.getPageN(page).getAsDict(PdfName.RESOURCES)?.getAsDict(PdfName.FONT) ?: continue
                for (key in fontDict.keys) {
                    val font = fontDict.getAsDict(key) ?: continue
                    val name = font.getAsName(PdfName.BASEFONT)?.toString() ?: key.toString()
                    result[name] = embedded(font)
                }
            }
            return result
        }

    private fun embedded(font: PdfDictionary): Boolean {
        val descendants: PdfArray? = font.getAsArray(PdfName.DESCENDANTFONTS)
        val target = descendants?.getAsDict(0) ?: font
        val descriptor = target.getAsDict(PdfName.FONTDESCRIPTOR) ?: return false
        return descriptor.contains(PdfName.FONTFILE) || descriptor.contains(PdfName.FONTFILE2) ||
            descriptor.contains(PdfName.FONTFILE3)
    }
}
