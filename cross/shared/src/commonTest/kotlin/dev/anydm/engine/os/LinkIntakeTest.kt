package dev.anydm.engine.os

import dev.anydm.engine.core.DownloadKind
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/** What a string handed to the app is (OSI-07). */
class LinkIntakeTest {
    private val hash = "c12fe1c06bba254a9dc9f519b335aa7c1367a88a"

    private fun kindOf(raw: String) = LinkIntake.parse(raw)?.kind

    @Test
    fun webAddressesAreDirectDownloads() {
        kindOf("https://files.example.com/a.zip") shouldBe DownloadKind.DIRECT
        kindOf("http://example.com/a.zip?x=1") shouldBe DownloadKind.DIRECT
        kindOf("  https://example.com/a.zip  ") shouldBe DownloadKind.DIRECT
    }

    @Test
    fun remoteFileSchemesAreFtpDownloads() {
        kindOf("ftp://example.com/a.zip") shouldBe DownloadKind.FTP
        kindOf("ftps://example.com/a.zip") shouldBe DownloadKind.FTP
        kindOf("sftp://example.com/a.zip") shouldBe DownloadKind.FTP
    }

    @Test
    fun magnetLinksAndTorrentFilesAreTorrents() {
        kindOf("magnet:?xt=urn:btih:$hash&dn=x") shouldBe DownloadKind.TORRENT
        kindOf("https://example.com/x.torrent") shouldBe DownloadKind.TORRENT
        kindOf("https://example.com/x.torrent?token=1") shouldBe DownloadKind.TORRENT
        kindOf("file:///home/me/x.torrent") shouldBe DownloadKind.TORRENT
    }

    @Test
    fun aPathToATorrentFileBecomesAFileAddress() {
        LinkIntake.parse("/home/me/my file.torrent")?.link shouldBe "file:///home/me/my%20file.torrent"
        LinkIntake.parse("C:\\Users\\me\\x.torrent")?.link shouldBe "file:///C:/Users/me/x.torrent"
        kindOf("/home/me/x.torrent") shouldBe DownloadKind.TORRENT
    }

    @Test
    fun aPathWithAHashOrAQuestionMarkIsStillAPath() {
        LinkIntake.parse("/home/me/Show #1.torrent")?.link shouldBe "file:///home/me/Show%20%231.torrent"
        LinkIntake.parse("/home/me/what?.torrent")?.link shouldBe "file:///home/me/what%3F.torrent"
        kindOf("C:\\Users\\me\\Show #1.torrent") shouldBe DownloadKind.TORRENT
    }

    @Test
    fun aNetworkSharePathIsNothingForAnOpenToReachOut() {
        kindOf("\\\\host\\share\\x.torrent") shouldBe null
        kindOf("//host/share/x.torrent") shouldBe null
        kindOf("file:////host/share/x.torrent") shouldBe null
        kindOf("file://host/share/x.torrent") shouldBe null
    }

    @Test
    fun aFileAddressNeedsToEndInTorrent() {
        kindOf("file:///home/me/x.torrent?x=1") shouldBe null
        kindOf("file:///home/me/x.torrent#frag") shouldBe null
    }

    @Test
    fun anAnydmLinkCarriesTheLinkItOpens() {
        val wrapped = "anydm://add?url=https%3A%2F%2Fexample.com%2Fa.zip%3Fx%3D1"
        LinkIntake.parse(wrapped)?.link shouldBe "https://example.com/a.zip?x=1"
        kindOf(wrapped) shouldBe DownloadKind.DIRECT
        kindOf("anydm://add?url=magnet%3A%3Fxt%3Durn%3Abtih%3A$hash") shouldBe DownloadKind.TORRENT
    }

    @Test
    fun anAnydmLinkDoesNotNest() {
        kindOf("anydm://add?url=anydm%3A%2F%2Fadd%3Furl%3Dhttps%253A%252F%252Fa.example%252Fx") shouldBe null
    }

    @Test
    fun whatIsNotALinkIsNothing() {
        kindOf("") shouldBe null
        kindOf("   ") shouldBe null
        kindOf("hello world") shouldBe null
        kindOf("javascript:alert(1)") shouldBe null
        kindOf("file:///etc/passwd") shouldBe null
        kindOf("https://") shouldBe null
        kindOf("magnet:?dn=no-hash") shouldBe null
        kindOf("anydm://add") shouldBe null
        kindOf("anydm://other?url=https%3A%2F%2Fa.example%2Fx") shouldBe null
        kindOf("anydm://add?url=%ZZ") shouldBe null
    }
}
