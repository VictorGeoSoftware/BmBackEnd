package com.bm.backend.services

import java.io.ByteArrayInputStream
import java.io.InputStreamReader
import javax.xml.XMLConstants
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants.*
import javax.xml.stream.XMLStreamException
import javax.xml.stream.XMLStreamReader

/** A single parser configuration and budget per request, including identification and opaque subtrees. */
internal class TeachWorkbookInspectionXml(
    private val limits: TeachWorkbookInspectionLimits,
    private val checkpoint: () -> Unit,
    private val read: (String, Int) -> ByteArray,
) {
    private var bytesRead = 0
    private var events = 0
    private val factory = XMLInputFactory.newDefaultFactory().apply {
        setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, true)
        setProperty(XMLInputFactory.SUPPORT_DTD, false)
        setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
        setProperty(XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES, true)
        setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "")
        // JDK 17 StAX supports both native limits; independently count combined attributes/namespaces.
        setProperty("http://www.oracle.com/xml/jaxp/properties/maxElementDepth", limits.depth)
        setProperty("http://www.oracle.com/xml/jaxp/properties/elementAttributeLimit", limits.attributes)
        xmlResolver = javax.xml.stream.XMLResolver { _, _, _, _ -> throw XMLStreamException() }
    }

    fun metadata(part: String, ns: String, root: String, child: (XMLStreamReader) -> Unit) {
        parse(part, ns, root) {
            elements {
                workbookCheck(reader.namespaceURI == ns)
                child(reader)
                empty()
            }
        }
    }

    fun <T> parse(part: String, ns: String, root: String, block: Cursor.() -> T): T {
        val bytes = read(part, minOf(limits.xmlPart, limits.xmlTotal - bytesRead))
        bytesRead += bytes.size
        val bom = bytes.size >= 3 && bytes[0] == 0xef.toByte() && bytes[1] == 0xbb.toByte() && bytes[2] == 0xbf.toByte()
        val offset = if (bom) 3 else 0
        val source = object : ByteArrayInputStream(bytes, offset, bytes.size - offset) {
            override fun read(): Int { checkpoint(); return super.read() }
            override fun read(out: ByteArray, off: Int, len: Int): Int {
                checkpoint()
                return super.read(out, off, minOf(len, 8192))
            }
        }
        val input = InputStreamReader(source, Charsets.UTF_8.newDecoder())
        try {
            checkpoint()
            val parser = factory.createXMLStreamReader(input)
            try {
                val encoding = parser.characterEncodingScheme
                workbookCheck(encoding == null || encoding.equals("UTF-8", ignoreCase = true))
                val cursor = Cursor(parser)
                cursor.advanceDocument(START_ELEMENT)
                workbookCheck(parser.localName == root && parser.namespaceURI == ns)
                val value = cursor.block()
                workbookCheck(parser.eventType == END_ELEMENT && cursor.depth == 0)
                cursor.advanceDocument(END_DOCUMENT)
                return value
            } finally { parser.close() }
        } catch (failure: XMLStreamException) {
            // These are the JDK Xerces diagnostics for the two native limits above, not parser text on the wire.
            if (failure.message.orEmpty().lineSequence().any {
                it.startsWith("Message: JAXP00010006:") || it.startsWith("Message: JAXP00010002:")
            }) throw WorkbookBudgetExceeded()
            throw InvalidWorkbook()
        }
    }

    inner class Cursor(val reader: XMLStreamReader) {
        var depth = 0
            private set

        init { countEvent() } // START_DOCUMENT

        private fun countEvent() {
            checkpoint()
            if (++events > limits.events) throw WorkbookBudgetExceeded()
        }

        private fun next(): Int {
            checkpoint()
            return reader.next().also {
                countEvent()
                when (it) {
                    START_ELEMENT -> {
                        if (++depth > limits.depth || reader.attributeCount + reader.namespaceCount > limits.attributes) {
                            throw WorkbookBudgetExceeded()
                        }
                        workbookCheck(reader.getAttributeValue(XMLConstants.XML_NS_URI, "base") == null &&
                            reader.namespaceURI != "http://www.w3.org/2001/XInclude")
                    }
                    END_ELEMENT -> depth--
                    DTD, ENTITY_REFERENCE, PROCESSING_INSTRUCTION -> throw InvalidWorkbook()
                }
            }
        }

        fun advanceDocument(target: Int) {
            while (next() != target) workbookCheck(reader.eventType == COMMENT || reader.isWhiteSpace)
        }

        /** The callback owns exactly one direct child, leaving the cursor on its end tag. */
        fun elements(child: Cursor.() -> Unit) {
            val parentDepth = depth
            while (true) when (next()) {
                END_ELEMENT -> return
                START_ELEMENT -> {
                    child()
                    workbookCheck(reader.eventType == END_ELEMENT && depth == parentDepth)
                }
                COMMENT -> Unit
                CHARACTERS, SPACE, CDATA -> workbookCheck(reader.isWhiteSpace)
                else -> throw InvalidWorkbook()
            }
        }

        fun empty() = elements { throw InvalidWorkbook() }

        fun skip(visit: (Cursor.() -> Unit)? = null) {
            val until = depth - 1
            while (depth > until) {
                next()
                visit?.invoke(this)
            }
        }

        fun scalar(accept: (String) -> Unit = {}) {
            while (true) when (next()) {
                END_ELEMENT -> return
                COMMENT -> Unit
                CHARACTERS, SPACE, CDATA -> accept(reader.text)
                else -> throw InvalidWorkbook()
            }
        }
    }
}
