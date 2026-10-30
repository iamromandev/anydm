package dev.anydm.engine.http

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.test.Test

/** expandPattern: `file[01-50].zip` and friends (HTTP-15), as the old server's batch add expanded them. */
class BatchPatternTest {
    private fun links(
        pattern: String,
        limit: Int = MAX_PATTERN_LINKS,
    ) = expandPattern(pattern, limit).shouldBeInstanceOf<Expansion.Links>().urls

    @Test
    fun numbersKeepTheLeftPadding() {
        links("f[01-03].zip") shouldBe listOf("f01.zip", "f02.zip", "f03.zip")
        links("f[8-10].zip") shouldBe listOf("f8.zip", "f9.zip", "f10.zip")
        links("f[0-2]") shouldBe listOf("f0", "f1", "f2")
    }

    @Test
    fun letters() {
        links("[a-c]") shouldBe listOf("a", "b", "c")
        links("[X-Z]") shouldBe listOf("X", "Y", "Z")
    }

    @Test
    fun groupsMultiplyLastFastest() {
        links("[a-b][1-2]") shouldBe listOf("a1", "a2", "b1", "b2")
    }

    @Test
    fun otherBracketsAreLiteral() {
        links("f[abc][a-Z][1-].zip") shouldBe listOf("f[abc][a-Z][1-].zip")
        links("  https://h/f.zip  ") shouldBe listOf("https://h/f.zip")
    }

    @Test
    fun invalidPatterns() {
        expandPattern("   ") shouldBe Expansion.Invalid("the pattern is empty")
        expandPattern("f[50-01]") shouldBe Expansion.Invalid("the range [50-01] runs backwards")
        expandPattern("f[z-a]") shouldBe Expansion.Invalid("the range [z-a] runs backwards")
        expandPattern("[1-1001]") shouldBe Expansion.Invalid("the pattern names 1001 links; the most is 1000")
        expandPattern("[1-5][1-3]", limit = 10) shouldBe Expansion.Invalid("the pattern names 15 links; the most is 10")
        expandPattern("[1-99999999999][1-99999999999]") shouldBe Expansion.Invalid("the pattern names more than 1000 links")
        expandPattern("[1-99999999999999999999999]") shouldBe Expansion.Invalid("the pattern names more than 1000 links")
    }

    @Test
    fun exactlyTheLimitIsFine() {
        links("[1-1000]").size shouldBe 1000
    }

    @Test
    fun aLimitBelowOneIsABug() {
        shouldThrow<IllegalArgumentException> { expandPattern("f", limit = 0) }
    }

    @Test
    fun anIpv6HostIsLiteral() {
        links("http://[::1]/f[1-2].zip") shouldBe listOf("http://[::1]/f1.zip", "http://[::1]/f2.zip")
    }

    @Test
    fun paddingNeverTruncates() {
        links("[098-100]") shouldBe listOf("098", "099", "100")
    }
}
