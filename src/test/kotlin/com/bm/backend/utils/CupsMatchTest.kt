package com.bm.backend.utils

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CupsMatchTest {
    private val core = "ES0000000000000000AA"

    @Test
    fun `equal after trim and upper case`() {
        assertTrue(CupsMatch.same(" ${core}0f ", "${core}0F"))
        assertTrue(CupsMatch.same(core, core.lowercase()))
    }

    @Test
    fun `suffix on exactly one side matches the 20-char core`() {
        assertTrue(CupsMatch.same(core, "${core}0F"))
        assertTrue(CupsMatch.same("${core}0F", core))
    }

    @Test
    fun `different or blank values never match`() {
        assertFalse(CupsMatch.same("${core}0F", "${core}1F"))
        assertFalse(CupsMatch.same(core, "ES0000000000000000AB"))
        assertFalse(CupsMatch.same("", ""))
        assertFalse(CupsMatch.same(core, " "))
        assertFalse(CupsMatch.same(core.dropLast(1), "${core.dropLast(1)}0F0"))
        assertFalse(CupsMatch.same(core, "${core}0"))
    }
}
