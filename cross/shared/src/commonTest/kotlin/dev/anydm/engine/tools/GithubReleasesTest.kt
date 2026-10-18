package dev.anydm.engine.tools

import dev.anydm.engine.http.BytesBody
import dev.anydm.engine.http.FakeTransport
import dev.anydm.engine.http.HttpIoException
import dev.anydm.engine.http.HttpIoKind
import dev.anydm.engine.http.HttpRequest
import dev.anydm.engine.http.HttpResponse
import dev.anydm.engine.http.response
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class GithubReleasesTest {
    private val sums =
        """
        ${"a".repeat(64)}  yt-dlp
        ${"b".repeat(64)}  yt-dlp_macos
        ${"c".repeat(64)}  yt-dlp.exe
        ${"d".repeat(64)}  yt-dlp_linux
        ${"e".repeat(64)} *yt-dlp_linux_aarch64
        """.trimIndent()

    private val linux = Platform(OsFamily.LINUX, Arch.X64)

    private fun stableTag(
        tag: String = "2026.10.01",
        sumsText: String = sums,
        repo: String = "yt-dlp/yt-dlp",
    ): suspend (HttpRequest) -> HttpResponse =
        { request ->
            when (request.url) {
                "https://github.com/$repo/releases/latest" -> {
                    response(200, finalUrl = "https://github.com/$repo/releases/tag/$tag")
                }

                "https://github.com/$repo/releases/download/$tag/SHA2-256SUMS" -> {
                    response(200, finalUrl = request.url, body = BytesBody(sumsText.toByteArray()))
                }

                else -> {
                    response(404, finalUrl = request.url)
                }
            }
        }

    @Test
    fun stableReadsTheLatestTagFromTheRedirect() =
        runTest {
            val release = GithubReleases(FakeTransport(stableTag())).latest(YtDlpChannel.STABLE, linux)

            release shouldBe
                Release("2026.10.01", "https://github.com/yt-dlp/yt-dlp/releases/download/2026.10.01/yt-dlp_linux", "d".repeat(64))
        }

    @Test
    fun theSumsFileGivesEachPlatformsAssetItsSha256() =
        runTest {
            val releases = GithubReleases(FakeTransport(stableTag()))

            releases.latest(YtDlpChannel.STABLE, Platform(OsFamily.MACOS, Arch.ARM64))!!.sha256 shouldBe "b".repeat(64)
            releases.latest(YtDlpChannel.STABLE, Platform(OsFamily.WINDOWS, Arch.X64))!!.sha256 shouldBe "c".repeat(64)
            releases.latest(YtDlpChannel.STABLE, Platform(OsFamily.LINUX, Arch.ARM64))!!.sha256 shouldBe "e".repeat(64)
        }

    @Test
    fun nightlyUsesTheNightlyRepository() =
        runTest {
            val transport = FakeTransport(stableTag(tag = "2026.10.17.012345", repo = "yt-dlp/yt-dlp-nightly-builds"))

            val release = GithubReleases(transport).latest(YtDlpChannel.NIGHTLY, linux)

            release!!.version shouldBe "2026.10.17.012345"
            release.url shouldBe "https://github.com/yt-dlp/yt-dlp-nightly-builds/releases/download/2026.10.17.012345/yt-dlp_linux"
        }

    @Test
    fun aPlatformWithoutAnAssetHasNoRelease() =
        runTest {
            GithubReleases(FakeTransport(stableTag())).latest(YtDlpChannel.STABLE, Platform(OsFamily.WINDOWS, Arch.ARM64)) shouldBe null
        }

    @Test
    fun anAssetMissingFromTheSumsHasNoRelease() =
        runTest {
            val noLinux = sums.lines().filterNot { it.endsWith(" yt-dlp_linux") }.joinToString("\n")

            GithubReleases(FakeTransport(stableTag(sumsText = noLinux))).latest(YtDlpChannel.STABLE, linux) shouldBe null
        }

    @Test
    fun aBadHashLineIsIgnored() =
        runTest {
            val bad = "abc  yt-dlp_linux\n" + "F".repeat(64) + "  yt-dlp_linux"

            GithubReleases(FakeTransport(stableTag(sumsText = bad))).latest(YtDlpChannel.STABLE, linux) shouldBe null
        }

    @Test
    fun aFailedRequestHasNoRelease() =
        runTest {
            GithubReleases(FakeTransport { response(503) }).latest(YtDlpChannel.STABLE, linux) shouldBe null
            GithubReleases(
                FakeTransport { throw HttpIoException(HttpIoKind.CONNECT, "github.com") },
            ).latest(YtDlpChannel.STABLE, linux) shouldBe
                null
        }

    @Test
    fun aRedirectThatIsNoTagPageHasNoRelease() =
        runTest {
            val odd: suspend (HttpRequest) -> HttpResponse = { response(200, finalUrl = "https://github.com/login") }

            GithubReleases(FakeTransport(odd)).latest(YtDlpChannel.STABLE, linux) shouldBe null
        }

    @Test
    fun aTagThatIsNoVersionHasNoRelease() =
        runTest {
            GithubReleases(FakeTransport(stableTag(tag = "nightly"))).latest(YtDlpChannel.STABLE, linux) shouldBe null
        }
}
