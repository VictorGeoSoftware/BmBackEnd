package com.bm.backend.models

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class ConsumptionUploadTypeTest {
    @Test
    fun `resolves supported extensions case-insensitively`() {
        assertEquals(ConsumptionUploadType.PDF, ConsumptionUploadType.fromFileName("bill.PDF"))
        assertEquals(ConsumptionUploadType.JPEG, ConsumptionUploadType.fromFileName("bill.jpg"))
        assertEquals(ConsumptionUploadType.JPEG, ConsumptionUploadType.fromFileName("bill.JPEG"))
        assertEquals(ConsumptionUploadType.PNG, ConsumptionUploadType.fromFileName("a.b.png"))
    }

    @Test
    fun `rejects unsupported or missing extensions`() {
        listOf("x.heic", "x.gif", "x.txt", "x.webp", "noext", "", null).forEach {
            assertNull(ConsumptionUploadType.fromFileName(it), "expected null for $it")
        }
    }

    @Test
    fun `only images are flagged as image`() {
        assertFalse(ConsumptionUploadType.PDF.isImage)
        assertTrue(ConsumptionUploadType.JPEG.isImage)
        assertTrue(ConsumptionUploadType.PNG.isImage)
    }
}
