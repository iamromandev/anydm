package dev.anydm.engine.ftp

import dev.anydm.engine.http.Credentials
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class FtpUrlTest {
    private fun parsed(url: String) = FtpUrl.parse(url) ?: error("not parsed: $url")

    @Test
    fun parsesEachSchemeWithItsDefaultPort() {
        parsed("ftp://h/a").let {
            it.scheme shouldBe FtpScheme.FTP
            it.port shouldBe 21
        }
        parsed("ftps://h/a").let {
            it.scheme shouldBe FtpScheme.FTPS
            it.port shouldBe 990
        }
        parsed("ftpes://h/a").let {
            it.scheme shouldBe FtpScheme.FTPES
            it.port shouldBe 21
        }
        parsed("sftp://h/a").let {
            it.scheme shouldBe FtpScheme.SFTP
            it.port shouldBe 22
        }
    }

    @Test
    fun anExplicitPortWins() {
        parsed("ftp://h:2121/a").port shouldBe 2121
        parsed("sftp://u:p@h:2222/a").port shouldBe 2222
    }

    @Test
    fun pullsOutAndDecodesTheLogin() {
        parsed("ftp://al%40ice:p%3Aw@h/f").login shouldBe Credentials("al@ice", "p:w")
        parsed("ftp://h/f").login shouldBe null
    }

    @Test
    fun aColonInTheUserGivesNoLoginButTheUrlStillParses() {
        val url = parsed("ftp://a%3Ab:pw@h/f")
        url.login shouldBe null
        url.host shouldBe "h"
    }

    @Test
    fun percentEscapesSpacesAndUnicodeInThePathAreDecodedForTheFileName() {
        val url = parsed("ftp://h/dir/my%20file%20%C3%A9.txt")
        url.path shouldBe "/dir/my file é.txt"
        url.fileName shouldBe "my file é.txt"
    }

    @Test
    fun aBrokenEscapeIsNull() {
        FtpUrl.parse("ftp://h/a%zz") shouldBe null
    }

    @Test
    fun anIpv6HostParsesAndKeepsItsBrackets() {
        val url = parsed("sftp://[::1]:2222/a")
        url.host shouldBe "[::1]"
        url.port shouldBe 2222
        url.origin shouldBe "sftp://[::1]:2222"
        parsed("sftp://[::1]/a").port shouldBe 22
    }

    @Test
    fun anotherSchemeIsNull() {
        FtpUrl.parse("https://h/a") shouldBe null
        FtpUrl.parse("not a url") shouldBe null
    }

    @Test
    fun aMissingPathIsNull() {
        FtpUrl.parse("ftp://h") shouldBe null
        FtpUrl.parse("ftp://h/") shouldBe null
    }

    @Test
    fun aMissingHostOrABadPortIsNull() {
        FtpUrl.parse("ftp:///a") shouldBe null
        FtpUrl.parse("ftp://h:abc/a") shouldBe null
        FtpUrl.parse("ftp://h:70000/a") shouldBe null
    }

    @Test
    fun originHasNoUserinfoAndIsLowercase() {
        parsed("FTP://User:Pw@Example.COM/a").origin shouldBe "ftp://example.com:21"
        parsed("ftps://h:991/a").origin shouldBe "ftps://h:991"
    }

    @Test
    fun stripUserInfoRemovesOnlyTheLogin() {
        stripUserInfo("ftp://u:p@h:21/a?b#c") shouldBe "ftp://h:21/a?b#c"
        stripUserInfo("https://u@h/a") shouldBe "https://h/a"
        stripUserInfo("ftp://h/a@b") shouldBe "ftp://h/a@b"
        stripUserInfo("ftp://h/a") shouldBe "ftp://h/a"
        stripUserInfo("no scheme") shouldBe "no scheme"
    }
}
