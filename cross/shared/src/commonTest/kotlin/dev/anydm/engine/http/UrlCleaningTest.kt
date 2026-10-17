package dev.anydm.engine.http

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/** cleanUrl: redirect links unwrapped, tracking parameters stripped, everything else byte for byte (HTTP-04). */
class UrlCleaningTest {
    private fun check(vararg cases: Pair<String, String>) {
        for ((input, expected) in cases) withClue(input) { cleanUrl(input) shouldBe expected }
    }

    private fun googled(url: String) = "https://www.google.com/url?q=" + url.encodeComponent()

    private fun String.encodeComponent(): String =
        encodeToByteArray().joinToString("") { byte ->
            val c = byte.toInt().toChar()
            if ((c.isLetterOrDigit() && byte >= 0) || c in "-._~") {
                c.toString()
            } else {
                "%" + (byte.toInt() and 0xff).toString(16).uppercase().padStart(2, '0')
            }
        }

    @Test
    fun trackingParametersAreStripped() =
        check(
            "https://h/f.zip?utm_source=a&id=1&UTM_Medium=b" to "https://h/f.zip?id=1",
            "https://h/f.zip?fbclid=x" to "https://h/f.zip",
            "https://h/f.zip?a=1&gclid=x&b=%7E#frag" to "https://h/f.zip?a=1&b=%7E#frag",
            "https://h/f.zip?a=1&&utm_x=2&" to "https://h/f.zip?a=1&&",
            "https://h/f.zip?utm%5Fsource=a&b=1" to "https://h/f.zip?b=1",
        )

    @Test
    fun siAndPpOnlyWhereTheyTrack() =
        check(
            "https://youtu.be/abc?si=x&t=10" to "https://youtu.be/abc?t=10",
            "https://music.youtube.com/watch?v=1&si=x&pp=y" to "https://music.youtube.com/watch?v=1",
            "https://open.spotify.com/track/1?si=x" to "https://open.spotify.com/track/1",
            "https://open.spotify.com/track/1?pp=x" to "https://open.spotify.com/track/1?pp=x",
            "https://files.example.com/f.zip?si=1&pp=2" to "https://files.example.com/f.zip?si=1&pp=2",
        )

    @Test
    fun wrappersUnwrap() =
        check(
            "https://www.google.com/url?sa=t&q=https%3A%2F%2Fh%2Ff.zip" to "https://h/f.zip",
            "https://www.google.co.uk/url?url=https%3A%2F%2Fh%2Ff.zip" to "https://h/f.zip",
            "https://l.facebook.com/l.php?u=https%3A%2F%2Fh%2Ff.zip&h=AT0" to "https://h/f.zip",
            "https://lm.facebook.com/l.php?u=https%3A%2F%2Fh%2Ff.zip" to "https://h/f.zip",
            "https://l.instagram.com/?u=https%3A%2F%2Fh%2Ff.zip&e=1" to "https://h/f.zip",
            "https://www.youtube.com/redirect?event=x&q=https%3A%2F%2Fh%2Ff.zip" to "https://h/f.zip",
            "https://eur01.safelinks.protection.outlook.com/?url=https%3A%2F%2Fh%2Ff.zip&data=1" to "https://h/f.zip",
            "https://steamcommunity.com/linkfilter/?url=https://h/f.zip" to "https://h/f.zip",
            "https://steamcommunity.com/linkfilter/?u=https%3A%2F%2Fh%2Ff.zip" to "https://h/f.zip",
            "https://vk.com/away.php?to=https%3A%2F%2Fh%2Ff.zip" to "https://h/f.zip",
            "https://slack-redir.net/link?url=https%3A%2F%2Fh%2Ff.zip" to "https://h/f.zip",
            "https://disq.us/url?url=https%3A%2F%2Fh%2Ff.zip%3AaBc123&cuid=1" to "https://h/f.zip",
        )

    @Test
    fun theUnwrappedUrlIsCleaned() =
        check("https://www.google.com/url?q=https%3A%2F%2Fh%2Ff.zip%3Futm_source%3Dx%26id%3D1" to "https://h/f.zip?id=1")

    @Test
    fun nestedWrappersUnwrapUpToFive() {
        val five = (1..5).fold("https://h/f.zip") { url, _ -> googled(url) }
        cleanUrl(five) shouldBe "https://h/f.zip"
        val six = googled(five)
        cleanUrl(six) shouldBe googled("https://h/f.zip")
    }

    @Test
    fun aBadTargetLeavesTheWrapper() =
        check(
            "https://www.google.com/url?q=ftp%3A%2F%2Fh%2Ff" to "https://www.google.com/url?q=ftp%3A%2F%2Fh%2Ff",
            "https://www.google.com/url?sa=t" to "https://www.google.com/url?sa=t",
            "https://www.google.com/url?q=%zz&utm_source=x" to "https://www.google.com/url?q=%zz",
        )

    @Test
    fun nonHttpInputIsUnchanged() =
        check(
            "magnet:?xt=urn:btih:abc&utm_source=x" to "magnet:?xt=urn:btih:abc&utm_source=x",
            "" to "",
            "not a url" to "not a url",
        )

    @Test
    fun anIpv6HostIsParsed() = check("http://[::1]:8080/f?utm_source=a&x=1" to "http://[::1]:8080/f?x=1")

    @Test
    fun aQuestionMarkInTheFragmentIsNotAQuery() = check("https://h/p#a?utm_source=x" to "https://h/p#a?utm_source=x")

    @Test
    fun aTargetKeepsItsOwnQueryAndFragment() =
        check("https://www.google.com/url?q=https%3A%2F%2Fh%2Ff%3Fid%3D1%26utm_source%3Dx%23top" to "https://h/f?id=1#top")
}
