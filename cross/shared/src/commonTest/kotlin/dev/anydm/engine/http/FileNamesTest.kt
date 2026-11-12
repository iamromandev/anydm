package dev.anydm.engine.http

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FileNamesTest {
    @Test
    fun quotedFilename() = assertEquals("a.zip", nameFrom("attachment; filename=\"a.zip\"", "https://h/x"))

    @Test
    fun bareFilename() = assertEquals("a.zip", nameFrom("attachment; filename=a.zip", "https://h/x"))

    @Test
    fun rfc5987WinsOverPlainFilename() =
        assertEquals("b c.txt", nameFrom("attachment; filename=\"old.txt\"; filename*=UTF-8''b%20c.txt", "https://h/x"))

    @Test
    fun urlSegmentIsPercentDecoded() = assertEquals("b c.txt", nameFrom(null, "https://h/files/b%20c.txt"))

    @Test
    fun queryAndFragmentAreIgnored() = assertEquals("f.bin", nameFrom(null, "https://h/f.bin?x=1/2#frag"))

    @Test
    fun hostFallback() {
        assertEquals("example.com", nameFrom(null, "https://example.com"))
        assertEquals("example.com", nameFrom(null, "https://example.com/"))
        assertEquals("example.com", nameFrom(null, "http://user@example.com:8080/?q=1"))
    }

    @Test
    fun hostileValuesStayInsideFolder() {
        for (bad in listOf("../../x", "C:\\x", "/etc/x", "..", ".", "a/../b")) {
            val n = nameFrom("attachment; filename=\"$bad\"", "https://h/x")
            assertFalse('/' in n || '\\' in n, "$bad -> $n")
            assertTrue(n != "." && n != ".." && n.isNotEmpty(), "$bad -> $n")
        }
    }

    @Test
    fun hostileExamplesMapToExpectedForms() {
        assertEquals("C__x", sanitize("C:\\x"))
        assertEquals("_etc_x", sanitize("/etc/x"))
        assertEquals("_.._x", sanitize("../../x"))
    }

    @Test
    fun encodedSlashInUrlIsSanitized() {
        val n = nameFrom(null, "https://h/..%2F..%2Fx")
        assertFalse('/' in n)
    }

    @Test
    fun controlAndReservedCharsReplaced() = assertEquals("a_b_c_d_e_f_g_h_i_j.txt", sanitize("a\u0001b*c?d\"e<f>g|h:i\nj.txt"))

    @Test
    fun emptyBecomesFallback() {
        assertEquals("download", sanitize(""))
        assertEquals("download", sanitize(" . "))
    }

    @Test
    fun longNameKeepsExtensionAndCaps200() {
        val n = sanitize("a".repeat(500) + ".tar.gz")
        assertEquals(200, n.length)
        assertTrue(n.endsWith(".gz"))
    }

    @Test
    fun uniqueNameInsertsCounterBeforeExtension() {
        assertEquals("a.zip", uniqueName("a.zip") { false })
        assertEquals("a (1).zip", uniqueName("a.zip") { it == "a.zip" })
        assertEquals("a (2).zip", uniqueName("a.zip") { it == "a.zip" || it == "a (1).zip" })
    }

    @Test
    fun uniqueNameChecksPartFileToo() = assertEquals("a (1).zip", uniqueName("a.zip") { it == "a.zip.part" })

    @Test
    fun uniqueNameWithoutExtensionAndDotfile() {
        assertEquals("README (1)", uniqueName("README") { it == "README" })
        assertEquals(".bashrc (1)", uniqueName(".bashrc") { it == ".bashrc" })
    }
}
