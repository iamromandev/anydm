package dev.anydm.engine.torrent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class MagnetTest {
    private val hex = "08ada5a7a6183aae1e09d831df6748d566095a10"
    private val base32 = "BCW2LJ5GDA5K4HQJ3AY56Z2I2VTASWQQ"

    private fun ok(text: String): MagnetLink = assertIs<MagnetResult.Ok>(parseMagnet(text), text).link

    private val tr1 = "udp%3A%2F%2Ftracker.example%3A1337"
    private val tr2 = "https%3A%2F%2Ft2.example%2Fannounce"

    // AC1
    @Test
    fun hexUppercaseHexAndBase32GiveTheSameHashNameAndTrackers() {
        val forms =
            listOf(hex, hex.uppercase(), base32, base32.lowercase()).map { ok("magnet:?xt=urn:btih:$it&dn=Sintel&tr=$tr1&tr=$tr2") }
        for (link in forms) {
            assertEquals(hex, link.infoHashHex)
            assertEquals(20, link.infoHash.size)
            assertEquals("Sintel", link.name)
            assertEquals(listOf("udp://tracker.example:1337", "https://t2.example/announce"), link.trackers)
        }
    }

    @Test
    fun theSchemeKeysAndUrnAreCaseInsensitive() {
        val link = ok("MAGNET:?XT=URN:BTIH:$hex&DN=x&TR=$tr1")
        assertEquals(hex, link.infoHashHex)
        assertEquals("x", link.name)
    }

    @Test
    fun surroundingWhitespaceIsIgnored() {
        assertEquals(hex, ok("  magnet:?xt=urn:btih:$hex&tr=$tr1\n").infoHashHex)
    }

    @Test
    fun theNameIsPercentDecodedAndAPlusIsASpace() {
        assertEquals("Big Buck Bunny é", ok("magnet:?xt=urn:btih:$hex&dn=Big+Buck%20Bunny+%C3%A9&tr=$tr1").name)
    }

    @Test
    fun aMissingOrEmptyNameIsNull() {
        assertNull(ok("magnet:?xt=urn:btih:$hex&tr=$tr1").name)
        assertNull(ok("magnet:?xt=urn:btih:$hex&dn=&tr=$tr1").name)
    }

    @Test
    fun aBadPercentSequenceIsKeptAsWritten() {
        assertEquals("100%", ok("magnet:?xt=urn:btih:$hex&dn=100%&tr=$tr1").name)
        assertEquals("a%zzb", ok("magnet:?xt=urn:btih:$hex&dn=a%zzb&tr=$tr1").name)
    }

    @Test
    fun otherParametersAreIgnored() {
        val link = ok("magnet:?xt=urn:btih:$hex&xl=123&x.pe=1.2.3.4:5&ws=http%3A%2F%2Fweb.example%2F&kt=a+b&tr=$tr1")
        assertEquals(listOf("udp://tracker.example:1337"), link.trackers)
    }

    @Test
    fun theFirstUsableBtihIsTaken() {
        val link = ok("magnet:?xt=urn:btmh:1220$hex$hex&xt=urn:btih:nothash&xt=urn:btih:$hex&xt.1=urn:btih:${"a".repeat(40)}&tr=$tr1")
        assertEquals(hex, link.infoHashHex)
    }

    // AC2
    @Test
    fun aMissingOrMalformedHashIsInvalid() {
        listOf(
            "magnet:?dn=x&tr=$tr1",
            "magnet:?xt=urn:btih:${hex.dropLast(1)}&tr=$tr1",
            "magnet:?xt=urn:btih:${hex}0&tr=$tr1",
            "magnet:?xt=urn:btih:${"g".repeat(40)}&tr=$tr1",
            "magnet:?xt=urn:btih:${base32.dropLast(1)}&tr=$tr1",
            "magnet:?xt=urn:btih:${"1".repeat(32)}&tr=$tr1", // 1 is not in the base32 alphabet
            "magnet:?xt=urn:btih:&tr=$tr1",
            "magnet:?xt=urn:btmh:1220$hex&tr=$tr1",
            "magnet:?xt=$hex&tr=$tr1",
            "magnet:?",
            "magnet:",
            "",
            "   ",
            "http://example.com/?xt=urn:btih:$hex",
            "magnet:xt=urn:btih:$hex&tr=$tr1",
        ).forEach { assertEquals(MagnetResult.Invalid, parseMagnet(it), it) }
    }

    @Test
    fun aHashWithJunkIsInvalidEvenWhenTheTrackersAreFine() {
        assertEquals(MagnetResult.Invalid, parseMagnet("magnet:?xt=urn:btih:zzzz&tr=$tr1&dn=x"))
    }

    // AC3
    @Test
    fun noTrackerIsNoTrackers() {
        assertEquals(MagnetResult.NoTrackers, parseMagnet("magnet:?xt=urn:btih:$hex&dn=x"))
        assertEquals(MagnetResult.NoTrackers, parseMagnet("magnet:?xt=urn:btih:$hex&tr="))
    }

    @Test
    fun onlyOtherSchemesIsNoTrackers() {
        val wss = "wss%3A%2F%2Ftracker.example%2Fannounce"
        assertEquals(MagnetResult.NoTrackers, parseMagnet("magnet:?xt=urn:btih:$hex&tr=$wss&tr=ftp%3A%2F%2Fx.example%2F&tr=nonsense"))
    }

    @Test
    fun aTrackerWithNoHostIsNotUsable() {
        assertEquals(MagnetResult.NoTrackers, parseMagnet("magnet:?xt=urn:btih:$hex&tr=http%3A%2F%2F&tr=udp%3A%2F%2F%2Fx"))
    }

    @Test
    fun usableTrackersSurviveUnusableOnes() {
        val link = ok("magnet:?xt=urn:btih:$hex&tr=wss%3A%2F%2Fx.example&tr=$tr1&tr=HTTP%3A%2F%2FA.example%2Fa")
        assertEquals(listOf("udp://tracker.example:1337", "HTTP://A.example/a"), link.trackers)
    }

    @Test
    fun duplicateTrackersAreKeptOnce() {
        val link = ok("magnet:?xt=urn:btih:$hex&tr=$tr1&tr=$tr2&tr=$tr1&tr=${tr1.replace("%3A", ":")}")
        assertEquals(listOf("udp://tracker.example:1337", "https://t2.example/announce"), link.trackers)
    }

    @Test
    fun atMostMaxTrackersAreKept() {
        val many = (0 until MAX_TRACKERS + 20).joinToString("&") { "tr=http%3A%2F%2Ft$it.example%2Fannounce" }
        val link = ok("magnet:?xt=urn:btih:$hex&$many")
        assertEquals(MAX_TRACKERS, link.trackers.size)
        assertEquals("http://t0.example/announce", link.trackers.first())
    }

    @Test
    fun aTrackerUrlOverTheLimitIsDropped() {
        val long = "http://t.example/" + "a".repeat(MAX_TRACKER_URL_BYTES)
        val link = ok("magnet:?xt=urn:btih:$hex&tr=$long&tr=$tr1")
        assertEquals(listOf("udp://tracker.example:1337"), link.trackers)
    }

    @Test
    fun aTrackerUrlThatIsNotPlainAsciiIsDropped() {
        val link = ok("magnet:?xt=urn:btih:$hex&tr=http%3A%2F%2Ft.example%2Fa%20b&tr=http%3A%2F%2Ft.example%2F%C3%A9&tr=$tr1")
        assertEquals(listOf("udp://tracker.example:1337"), link.trackers)
    }

    @Test
    fun hashEqualityComparesBytes() {
        assertEquals(ok("magnet:?xt=urn:btih:$hex&tr=$tr1"), ok("magnet:?xt=urn:btih:$base32&tr=$tr1"))
    }

    // review: only ASCII hex digits make a hash; Unicode digits and letters are not hex
    @Test
    fun aHashWithNonAsciiDigitsOrLettersIsInvalid() {
        val arabicThree = "\u0663"
        val fullwidthA = "\uFF21"
        listOf(
            hex.take(39) + arabicThree,
            hex.take(39) + fullwidthA,
            fullwidthA + hex.drop(1),
        ).forEach { assertEquals(MagnetResult.Invalid, parseMagnet("magnet:?xt=urn:btih:$it&tr=$tr1"), it) }
    }

    @Test
    fun aBase32HashWithNonAsciiLettersIsInvalid() {
        // `ſ` (U+017F) and `ı` (U+0131) turn into ASCII `S` and `I` when upper-cased
        listOf(base32.replaceFirst('S', '\u017F'), base32.replaceFirst('I', '\u0131')).forEach {
            assertEquals(32, it.length)
            assertEquals(MagnetResult.Invalid, parseMagnet("magnet:?xt=urn:btih:$it&tr=$tr1"), it)
        }
    }

    // review: the name is shown to the user and may become a folder name: no control or direction-changing characters, bounded length
    @Test
    fun controlAndBidiCharactersAreStrippedFromTheName() {
        val name = "a\u0000b\u0007c\u202Ed\u202Ce\u2066f\u2069g\u200Eh\u200Fi\u061Cj\u0085k\u007Fl\nm\tn"
        // every byte but the letters a to n is written as %XX (the characters as UTF-8)
        val encoded =
            name.encodeToByteArray().joinToString("") { b ->
                val v = b.toInt() and 0xFF
                if (v in 'a'.code..'n'.code) v.toChar().toString() else "%" + v.toString(16).padStart(2, '0')
            }
        assertEquals("abcdefghijklmn", ok("magnet:?xt=urn:btih:$hex&dn=$encoded&tr=$tr1").name)
    }

    @Test
    fun aNameOfOnlyControlCharactersIsNull() {
        assertNull(ok("magnet:?xt=urn:btih:$hex&dn=%00%1B%E2%80%AE&tr=$tr1").name)
    }

    @Test
    fun aLongNameIsCutAt255Characters() {
        val link = ok("magnet:?xt=urn:btih:$hex&dn=${"a".repeat(1000)}&tr=$tr1")
        assertEquals(255, link.name!!.length)
    }

    @Test
    fun aNameIsNotCutInTheMiddleOfASurrogatePair() {
        // 254 letters, then an emoji (two UTF-16 units): the cut would fall between them
        val link = ok("magnet:?xt=urn:btih:$hex&dn=${"a".repeat(254)}%F0%9F%98%80%F0%9F%98%80&tr=$tr1")
        assertEquals("a".repeat(254), link.name)
    }
}
