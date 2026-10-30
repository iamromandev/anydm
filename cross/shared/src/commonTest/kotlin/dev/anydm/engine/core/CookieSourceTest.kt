package dev.anydm.engine.core

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.test.Test

class CookieSourceTest {
    private val absolute = Path(SystemTemporaryDirectory, "cookies.txt").toString()

    @Test
    fun aFileMustBeAbsolute() {
        shouldThrow<IllegalArgumentException> { CookieSource.File("cookies.txt") }
        shouldThrow<IllegalArgumentException> { CookieSource.File("") }
        shouldThrow<IllegalArgumentException> { CookieSource.File("$absolute\nmore") }
        CookieSource.File(absolute).path shouldBe absolute
    }

    @Test
    fun aBrowserMustBeKnown() {
        shouldThrow<IllegalArgumentException> { CookieSource.Browser("netscape") }
        shouldThrow<IllegalArgumentException> { CookieSource.Browser("--exec") }
        for (name in CookieSource.BROWSERS) CookieSource.Browser(name).name shouldBe name
    }

    @Test
    fun aProfileCannotStartWithADashOrHoldAColon() {
        shouldThrow<IllegalArgumentException> { CookieSource.Browser("chrome", "-x") }
        shouldThrow<IllegalArgumentException> { CookieSource.Browser("chrome", "a:b") }
        shouldThrow<IllegalArgumentException> { CookieSource.Browser("chrome", " ") }
        shouldThrow<IllegalArgumentException> { CookieSource.Browser("chrome", "a\nb") }
        CookieSource.Browser("chrome", "Profile 1").profile shouldBe "Profile 1"
    }

    @Test
    fun flagsForFileAndBrowser() {
        CookieSource.None.ytDlpFlags() shouldBe emptyList()
        CookieSource.File(absolute).ytDlpFlags() shouldBe listOf("--cookies", absolute)
        CookieSource.Browser("firefox").ytDlpFlags() shouldBe listOf("--cookies-from-browser", "firefox")
        CookieSource.Browser("chrome", "Profile 1").ytDlpFlags() shouldBe listOf("--cookies-from-browser", "chrome:Profile 1")
    }

    @Test
    fun encodeAndDecodeRoundTrip() {
        for (source in listOf(CookieSource.File(absolute), CookieSource.Browser("firefox"), CookieSource.Browser("chrome", "Profile 1"))) {
            CookieSource.decode(CookieSource.encode(source)) shouldBe source
        }
        CookieSource.encode(CookieSource.Browser("chrome", "Default")) shouldBe "browser:chrome:Default"
    }

    @Test
    fun decodeRefusesJunk() {
        for (junk in listOf("", "none", "file:relative.txt", "browser:netscape", "browser:", "browser:chrome:", "ftp:/x", "file:")) {
            CookieSource.decode(junk) shouldBe null
        }
    }
}
