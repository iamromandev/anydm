package dev.anydm.engine.net

import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.test.assertFailsWith

/** Which hosts skip the proxy (NET-15). */
class BypassListTest {
    private fun list(vararg patterns: String) = BypassList.parse(patterns.toList())

    @Test
    fun loopbackAlwaysBypasses() {
        val none = BypassList.parse(emptyList())
        for (host in listOf("localhost", "LOCALHOST", "a.localhost", "127.0.0.1", "127.9.9.9", "::1", "[::1]")) {
            none.matches(host) shouldBe true
        }
        none.matches("example.com") shouldBe false
    }

    @Test
    fun exactHostIgnoresCase() {
        val bypass = list("Intranet.Example.com")
        bypass.matches("intranet.example.com") shouldBe true
        bypass.matches("other.example.com") shouldBe false
        bypass.matches("x.intranet.example.com") shouldBe false
    }

    @Test
    fun domainPatternsCoverTheDomainAndItsSubdomains() {
        for (pattern in listOf("*.corp.test", ".corp.test")) {
            val bypass = list(pattern)
            bypass.matches("corp.test") shouldBe true
            bypass.matches("a.b.corp.test") shouldBe true
            bypass.matches("notcorp.test") shouldBe false
            bypass.matches("corp.test.evil.com") shouldBe false
        }
    }

    @Test
    fun localMatchesNamesWithoutADot() {
        val bypass = list("<local>")
        bypass.matches("printer") shouldBe true
        bypass.matches("printer.lan") shouldBe false
        bypass.matches("10.0.0.5") shouldBe false
    }

    @Test
    fun ipv4Cidr() {
        val bypass = list("10.0.0.0/8", "192.168.1.128/25")
        bypass.matches("10.200.3.4") shouldBe true
        bypass.matches("11.0.0.1") shouldBe false
        bypass.matches("192.168.1.200") shouldBe true
        bypass.matches("192.168.1.100") shouldBe false
        bypass.matches("intranet") shouldBe false
    }

    @Test
    fun ipv6CidrAndLiterals() {
        val bypass = list("fd00::/8", "2001:db8::1")
        bypass.matches("fd12:3456::7") shouldBe true
        bypass.matches("[fd12:3456::7]") shouldBe true
        bypass.matches("fe80::1") shouldBe false
        bypass.matches("2001:db8::1") shouldBe true
        bypass.matches("2001:db8:0:0:0:0:0:1") shouldBe true
        bypass.matches("2001:db8::2") shouldBe false
    }

    @Test
    fun anIpv4AddressWrittenAsIpv6IsStillThatAddress() {
        val none = BypassList.parse(emptyList())
        none.matches("::ffff:127.0.0.1") shouldBe true
        none.matches("::ffff:7f00:1") shouldBe true
        none.matches("::ffff:8.8.8.8") shouldBe false
        list("10.0.0.0/8").matches("::ffff:10.1.2.3") shouldBe true
    }

    @Test
    fun aSignOrNonHexDigitIsNotAnIpv6Group() {
        list("fd00::/8").matches("-1::1") shouldBe false
        list("fd00::/8").matches("+1::1") shouldBe false
    }

    @Test
    fun aSingleIpv4Literal() {
        val bypass = list("203.0.113.9")
        bypass.matches("203.0.113.9") shouldBe true
        bypass.matches("203.0.113.10") shouldBe false
    }

    @Test
    fun blankPatternsAreIgnoredAndBadOnesRejected() {
        list("", "  ").matches("example.com") shouldBe false
        assertFailsWith<IllegalArgumentException> { list("10.0.0.0/33") }
        assertFailsWith<IllegalArgumentException> { list("fd00::/129") }
        assertFailsWith<IllegalArgumentException> { list("10.0.0.0/x") }
        assertFailsWith<IllegalArgumentException> { list("a b.com") }
    }
}
