package com.bm.backend.models

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CupsValidationTest {

    // --- computeControlCode -------------------------------------------------

    @Test
    fun `control code for all zeros is TT`() {
        assertEquals("TT", CupsValidation.computeControlCode("0000000000000000"))
    }

    @Test
    fun `control code for value 1 is TR`() {
        // mod = 1 → c1=0 (T), c2=1 (R)
        assertEquals("TR", CupsValidation.computeControlCode("0000000000000001"))
    }

    @Test
    fun `control code for known supply identifier is RC`() {
        // 31607515707001 mod 529 = 43 → c1=1 (R), c2=20 (C)
        assertEquals("RC", CupsValidation.computeControlCode("0031607515707001"))
    }

    @Test
    fun `computeControlCode rejects non-16-digit input`() {
        assertNull(CupsValidation.computeControlCode(""))
        assertNull(CupsValidation.computeControlCode("123"))
        assertNull(CupsValidation.computeControlCode("00000000000000001"))
        assertNull(CupsValidation.computeControlCode("ABCDEFGHIJKLMNOP"))
    }

    // --- isValid ------------------------------------------------------------

    @Test
    fun `valid 20-char CUPS passes`() {
        assertTrue(CupsValidation.isValid("ES0031607515707001RC"))
    }

    @Test
    fun `valid 22-char CUPS with frontier passes`() {
        assertTrue(CupsValidation.isValid("ES0031607515707001RC0F"))
        assertTrue(CupsValidation.isValid("ES0031607515707001RC1P"))
    }

    @Test
    fun `lowercase plus separators are normalized`() {
        assertTrue(CupsValidation.isValid("es 0031-6075-1570-7001 rc"))
        assertTrue(CupsValidation.isValid("  ES.0031.6075.1570.7001.RC.0F  "))
    }

    @Test
    fun `wrong control code is rejected`() {
        assertFalse(CupsValidation.isValid("ES0031607515707001AA"))
        assertFalse(CupsValidation.isValid("ES0031607515707001RD"))
    }

    @Test
    fun `letters in supply identifier are rejected`() {
        assertFalse(CupsValidation.isValid("ES003160AB15707001RC"))
    }

    @Test
    fun `wrong length is rejected`() {
        assertFalse(CupsValidation.isValid("ES003160751570700"))           // too short
        assertFalse(CupsValidation.isValid("ES0031607515707001RC0FX"))     // too long
    }

    @Test
    fun `invalid frontier suffix is rejected`() {
        // Frontier must be digit + letter.
        assertFalse(CupsValidation.isValid("ES0031607515707001RCFF"))
        assertFalse(CupsValidation.isValid("ES0031607515707001RC00"))
    }

    @Test
    fun `non-ES prefix is rejected`() {
        assertFalse(CupsValidation.isValid("PT0031607515707001RC"))
        assertFalse(CupsValidation.isValid("0031607515707001RC"))
    }

    @Test
    fun `empty string is rejected`() {
        assertFalse(CupsValidation.isValid(""))
        assertFalse(CupsValidation.isValid("   "))
    }
}
