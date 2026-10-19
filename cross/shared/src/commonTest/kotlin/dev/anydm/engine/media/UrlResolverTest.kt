package dev.anydm.engine.media

import io.kotest.matchers.shouldBe
import kotlin.test.Test

class UrlResolverTest {
    private val base = "https://a.test/x/y.m3u8?token=1"

    @Test
    fun aSiblingResolvesInTheSameFolderAndDropsTheBaseQuery() {
        resolveUrl(base, "z.ts") shouldBe "https://a.test/x/z.ts"
        resolveUrl(base, "./z.ts") shouldBe "https://a.test/x/z.ts"
    }

    @Test
    fun dotDotGoesUpAndClampsAtTheRoot() {
        resolveUrl(base, "../z.ts") shouldBe "https://a.test/z.ts"
        resolveUrl(base, "../../../z.ts") shouldBe "https://a.test/z.ts"
    }

    @Test
    fun anAbsolutePathKeepsOnlyTheOrigin() {
        resolveUrl(base, "/z.ts") shouldBe "https://a.test/z.ts"
    }

    @Test
    fun aSchemeRelativeUrlTakesTheBasesScheme() {
        resolveUrl(base, "//b.test/z.ts") shouldBe "https://b.test/z.ts"
    }

    @Test
    fun anAbsoluteUrlIsLeftAlone() {
        resolveUrl(base, "https://c.test/z?t=1") shouldBe "https://c.test/z?t=1"
    }

    @Test
    fun theRefsQueryIsKept() {
        resolveUrl(base, "z.ts?t=1") shouldBe "https://a.test/x/z.ts?t=1"
    }

    @Test
    fun aPortIsKept() {
        resolveUrl("http://localhost:8080/a/b.m3u8", "c.ts") shouldBe "http://localhost:8080/a/c.ts"
    }

    @Test
    fun aBaseWithoutAPathResolvesAtTheRoot() {
        resolveUrl("https://a.test", "z.ts") shouldBe "https://a.test/z.ts"
    }
}
