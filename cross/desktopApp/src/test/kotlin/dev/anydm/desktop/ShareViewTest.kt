package dev.anydm.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ShareViewTest {
    private fun percents(view: ShareView) = view.parts.map { it.percent }

    @Test
    fun `two networks split 70 and 30, the larger first`() {
        val view = shareView(mapOf("usb" to 30L, "wifi" to 70L), mapOf("wifi" to "Wi-Fi", "usb" to "USB"))
        assertEquals(listOf("Wi-Fi", "USB"), view.parts.map { it.name })
        assertEquals(listOf(70, 30), percents(view))
        assertTrue(view.multiple)
        assertEquals("Wi-Fi 70% · USB 30%", view.label)
    }

    @Test
    fun `three equal thirds round to 34, 33 and 33`() {
        val view = shareView(mapOf("a" to 100L, "b" to 100L, "c" to 100L), emptyMap())
        assertEquals(listOf(34, 33, 33), percents(view))
        assertEquals(listOf("a", "b", "c"), view.parts.map { it.key })
    }

    @Test
    fun `percents always sum to 100`() {
        val view = shareView(mapOf("a" to 1L, "b" to 2L, "c" to 4L, "d" to 7L, "e" to 13L), emptyMap())
        assertEquals(100, percents(view).sum())
    }

    @Test
    fun `a network with no bytes is left out, so one network is not a share`() {
        val view = shareView(mapOf("a" to 500L, "b" to 0L), emptyMap())
        assertEquals(1, view.parts.size)
        assertEquals(listOf(100), percents(view))
        assertFalse(view.multiple)
    }

    @Test
    fun `no share at all is empty and not shown`() {
        val view = shareView(emptyMap(), emptyMap())
        assertTrue(view.parts.isEmpty())
        assertFalse(view.multiple)
    }

    @Test
    fun `an unknown network shows its key`() {
        val view = shareView(mapOf("en7" to 1L, "wifi" to 1L), mapOf("wifi" to "Wi-Fi"))
        assertEquals(setOf("en7", "Wi-Fi"), view.parts.map { it.name }.toSet())
    }

    @Test
    fun `the unknown network key reads Unknown network`() {
        val view = shareView(mapOf("unknown" to 1L, "wifi" to 1L), mapOf("wifi" to "Wi-Fi"))
        assertEquals(setOf("Unknown network", "Wi-Fi"), view.parts.map { it.name }.toSet())
    }

    @Test
    fun `two networks with one name get their keys appended`() {
        val view = shareView(mapOf("a" to 2L, "b" to 1L, "c" to 1L), mapOf("a" to "Ethernet", "b" to "Ethernet", "c" to "Wi-Fi"))
        assertEquals(listOf("Ethernet (a)", "Ethernet (b)", "Wi-Fi"), view.parts.sortedBy { it.key }.map { it.name })
    }

    @Test
    fun `a share under one percent reads less than 1 and the percents still sum to 100`() {
        val view = shareView(mapOf("a" to 999L, "b" to 1L), emptyMap())
        assertEquals(100, percents(view).sum())
        assertEquals(listOf("100%", "<1%"), view.parts.map { it.percentText })
        assertEquals("a 100% · b <1%", view.label)
    }

    @Test
    fun `a whole-number share reads as its percent`() {
        assertEquals(listOf("70%", "30%"), shareView(mapOf("a" to 70L, "b" to 30L), emptyMap()).parts.map { it.percentText })
    }
}
