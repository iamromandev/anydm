package dev.anydm.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class VersionTest {
    private fun v(text: String) = Version.parse(text) ?: error("not a version: $text")

    @Test
    fun `a plain version parses with or without a leading v`() {
        assertEquals(v("1.2.3"), v("v1.2.3"))
        assertEquals(v("1.2.3"), v("V1.2.3"))
        assertEquals("1.2.3", v("v1.2.3").toString())
    }

    @Test
    fun `missing minor and patch count as zero`() {
        assertEquals(v("2.0.0"), v("2"))
        assertEquals(v("2.1.0"), v("2.1"))
    }

    @Test
    fun `build metadata is ignored`() {
        assertEquals(v("1.2.3"), v("1.2.3+build.5"))
        assertEquals(0, v("1.2.3+a").compareTo(v("1.2.3+b")))
    }

    @Test
    fun `versions compare by number not by text`() {
        assertTrue(v("1.10.0") > v("1.9.9"))
        assertTrue(v("2.0.0") > v("1.99.99"))
        assertTrue(v("1.0.1") > v("1.0.0"))
        assertFalse(v("1.0.0") > v("1.0.0"))
    }

    @Test
    fun `a pre-release sorts below its release and is marked`() {
        assertTrue(v("1.0.0-rc.1") < v("1.0.0"))
        assertTrue(v("1.0.0-rc.1").isPreRelease)
        assertFalse(v("1.0.0").isPreRelease)
        assertTrue(v("1.0.0-alpha") < v("1.0.0-alpha.1"))
        assertTrue(v("1.0.0-alpha.2") < v("1.0.0-alpha.10"))
        assertTrue(v("1.0.0-alpha.1") < v("1.0.0-beta"))
        assertTrue(v("1.0.0-1") < v("1.0.0-alpha"))
    }

    @Test
    fun `garbage is not a version`() {
        val garbage =
            listOf(
                "",
                " ",
                "v",
                "latest",
                "nightly",
                "1.2.3.4",
                "1..2",
                "1.2.",
                ".1",
                "1.x.3",
                "-1.2.3",
                "1.2.3-",
                "1.2.3+",
                "1.2.-3",
                "v v1.2.3",
                "1.2.3 beta",
                "1.2.99999999999999999999",
                "1.2.3-ü",
                "a".repeat(100),
                "1.2.3-rc..1",
            )
        for (text in garbage) assertNull(Version.parse(text), "'$text'")
    }

    @Test
    fun `surrounding whitespace is tolerated`() {
        assertNotNull(Version.parse(" v1.0.0\n"))
    }

    @Test
    fun `the running version is the one in the build file`() {
        val built =
            Regex("""val appVersion = "([^"]+)"""")
                .find(java.io.File("build.gradle.kts").readText())!!
                .groupValues[1]
        assertEquals(v(built), AppVersion.current)
        // The installers carry the same number as the running app: both come from the one line.
        assertTrue(
            java.io
                .File("build.gradle.kts")
                .readText()
                .contains("packageVersion = appVersion"),
        )
        assertFalse(AppVersion.current!!.isPreRelease)
    }
}
