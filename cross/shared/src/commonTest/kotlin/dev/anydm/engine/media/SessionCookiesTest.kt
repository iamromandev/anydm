package dev.anydm.engine.media

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import kotlin.test.Test

class SessionCookiesTest {
    private val page =
        """{"id":"a","title":"T","cookies":"TOP=secret0","formats":[""" +
            """{"format_id":"1","url":"https://cdn.test/1","cookies":"SID=secret1; Domain=.example.com",""" +
            """"http_headers":{"Referer":"https://r.test"}},""" +
            """{"format_id":"2","url":"https://cdn.test/2"}],""" +
            """"requested_formats":[{"format_id":"1","cookies":"SID=secret2"}]}"""

    @Test
    fun theCookiesTheJarGaveAreDroppedFromEveryFormat() {
        val kept = withoutSessionCookies(page)

        kept shouldNotContain "secret"
        kept shouldNotContain "\"cookies\""
    }

    @Test
    fun everythingElseIsKept() {
        val kept = withoutSessionCookies(page)
        val info = (YtDlpJson.parse(kept) as MediaParse.Ok).info

        info.streams.map { it.formatId } shouldBe listOf("1", "2")
        info.streams.first().headers shouldBe listOf("Referer" to "https://r.test")
    }

    @Test
    fun textThatIsNotJsonIsReturnedAsItIs() {
        withoutSessionCookies("not json") shouldBe "not json"
    }
}
