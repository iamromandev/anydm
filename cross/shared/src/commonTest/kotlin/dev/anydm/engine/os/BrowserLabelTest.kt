package dev.anydm.engine.os

import io.kotest.matchers.shouldBe
import kotlin.test.Test

/** What a paired browser is called in the list: short, readable, and nothing a browser can use to fool the eye. */
class BrowserLabelTest {
    private val chromeMac =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/130.0.0.0 Safari/537.36"
    private val firefoxLinux = "Mozilla/5.0 (X11; Linux x86_64; rv:131.0) Gecko/20100101 Firefox/131.0"
    private val edgeWindows =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/130.0.0.0 Safari/537.36 Edg/130.0.2849.46"
    private val safari =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) " +
            "Version/18.1 Safari/605.1.15"

    @Test
    fun aKnownBrowserIsNamedWithItsMajorVersionAndSystem() {
        browserLabel("chrome-extension://abc", chromeMac) shouldBe "Chrome 130 (macOS)"
        browserLabel("moz-extension://abc", firefoxLinux) shouldBe "Firefox 131 (Linux)"
        browserLabel("chrome-extension://abc", edgeWindows) shouldBe "Edge 130 (Windows)"
        browserLabel("chrome-extension://abc", safari) shouldBe "Safari 18 (macOS)"
    }

    @Test
    fun withoutAnAgentTheOriginSaysWhatKindOfBrowser() {
        browserLabel("chrome-extension://abcdefghijklmnop", null) shouldBe "Chrome extension"
        browserLabel("moz-extension://5f1c", "  ") shouldBe "Firefox extension"
        browserLabel(null, null) shouldBe "Browser"
    }

    @Test
    fun anAgentWeDoNotKnowIsShownCleaned() {
        browserLabel("chrome-extension://abc", "curl/8.4.0") shouldBe "curl/8.4.0"
    }

    @Test
    fun aLabelHasNoControlOrFormatCharactersAndIsAtMostSixtyLong() {
        val hostile = "evil\r\n\u0000\u001b[31m\u202eAgent\u200b   with\tgaps" + "x".repeat(200)

        val label = browserLabel("chrome-extension://abc", hostile)

        label shouldBe "evil [31mAgent with gaps" + "x".repeat(36)
    }

    @Test
    fun aLongKnownAgentStillMakesAShortLabel() {
        browserLabel("chrome-extension://abc", chromeMac + " " + "pad ".repeat(100)).length shouldBe 18
    }

    @Test
    fun cleaningKeepsOrdinaryTextAndDropsTheRest() {
        cleanLabel("  Work  laptop ") shouldBe "Work laptop"
        cleanLabel("\u0007\u0008") shouldBe ""
        cleanLabel("a".repeat(61)).length shouldBe 60
    }
}
