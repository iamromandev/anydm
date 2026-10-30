package dev.anydm.engine.torrent

import io.kotest.matchers.shouldBe
import kotlin.test.Test

class TorrentSourceTest {
    private val hex = "0123456789abcdef0123456789abcdef01234567"
    private val base32 = "AERUKZ4JVPG66AJDIVTYTK6N54ASGRLH"

    @Test
    fun aMagnetWith40HexParses() {
        TorrentSource.parse("magnet:?xt=urn:btih:$hex&dn=Name&tr=udp%3A%2F%2Ft.example%3A80") shouldBe
            TorrentSource.Magnet("magnet:?xt=urn:btih:$hex&dn=Name&tr=udp%3A%2F%2Ft.example%3A80")
    }

    @Test
    fun base32HashParses() {
        TorrentSource.parse("magnet:?xt=urn:btih:$base32") shouldBe TorrentSource.Magnet("magnet:?xt=urn:btih:$base32")
    }

    @Test
    fun theHashMayComeAfterOtherParameters() {
        TorrentSource.parse("magnet:?dn=x&xt=urn:btmh:1220abcd&xt=urn:btih:$hex") shouldBe
            TorrentSource.Magnet("magnet:?dn=x&xt=urn:btmh:1220abcd&xt=urn:btih:$hex")
    }

    @Test
    fun aMagnetWithoutXtIsNull() {
        TorrentSource.parse("magnet:?dn=Name") shouldBe null
        TorrentSource.parse("magnet:") shouldBe null
    }

    @Test
    fun aBadHashIsNull() {
        TorrentSource.parse("magnet:?xt=urn:btih:xyz") shouldBe null
        TorrentSource.parse("magnet:?xt=urn:btih:${hex.dropLast(1)}") shouldBe null
        TorrentSource.parse("magnet:?xt=urn:btih:${"g".repeat(40)}") shouldBe null
        TorrentSource.parse("magnet:?xt=urn:sha1:$hex") shouldBe null
    }

    @Test
    fun httpAndHttpsUrlsParse() {
        TorrentSource.parse("https://example.com/a/x.torrent") shouldBe TorrentSource.Url("https://example.com/a/x.torrent")
        TorrentSource.parse("http://example.com/x.torrent?k=1") shouldBe TorrentSource.Url("http://example.com/x.torrent?k=1")
        TorrentSource.parse("https://") shouldBe null
    }

    @Test
    fun aFileUrlParses() {
        TorrentSource.parse("file:///tmp/a%20b.torrent") shouldBe TorrentSource.LocalFile("/tmp/a b.torrent")
        TorrentSource.parse("file:///C:/Users/me/x.torrent") shouldBe TorrentSource.LocalFile("C:/Users/me/x.torrent")
        TorrentSource.parse("file://relative.torrent") shouldBe null
    }

    @Test
    fun anythingElseIsNull() {
        for (text in listOf("", "  ", "ftp://example.com/x.torrent", "x.torrent", "javascript:alert(1)", "/tmp/x.torrent")) {
            TorrentSource.parse(text) shouldBe null
        }
    }
}
