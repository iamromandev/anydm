package dev.anydm.engine.media

import dev.anydm.engine.core.DownloadId
import dev.anydm.engine.core.ErrorClass
import dev.anydm.engine.net.ProxyEndpoint
import dev.anydm.engine.net.ProxyRoute
import dev.anydm.engine.net.ProxyRouter
import dev.anydm.engine.net.ProxyType
import dev.anydm.engine.net.withProxy
import dev.anydm.engine.platform.FakeProcessRunner
import dev.anydm.engine.protocol.StepResult
import dev.anydm.engine.tools.ResolvedTool
import dev.anydm.engine.tools.Tool
import dev.anydm.engine.tools.ToolVersion
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlin.test.Test

class YtDlpRunnerTest {
    private val tool = ResolvedTool(Tool.YT_DLP, ToolVersion.parse("2026.1.1")!!, mapOf("yt-dlp" to Path("/tools/yt-dlp")), managed = true)
    private val url = "https://www.example.com/watch?v=abc"
    private val json = """{"id":"x","title":"T"}"""

    private fun fake(
        stdout: List<String> = listOf(json),
        stderr: List<String> = emptyList(),
    ) = FakeProcessRunner(listOf(FakeProcessRunner.Scripted(listOf("/tools/yt-dlp"), 0, stdout, stderr)))

    @Test
    fun extractionIgnoresTheUsersConfig() =
        runTest {
            val runner = fake()

            YtDlpRunner(runner).extract(tool, null, url)

            runner.started.single().command shouldBe
                listOf("/tools/yt-dlp", "--ignore-config", "-J", "--no-playlist", "--", url)
        }

    @Test
    fun cookieFlagsAreAddedToTheExtraction() =
        runTest {
            val runner = fake()

            YtDlpRunner(runner).extract(tool, null, url, listOf("--cookies", "/c/cookies.txt", "--sleep-requests", "1.0"))

            runner.started
                .single()
                .command
                .takeLast(6) shouldBe listOf("--cookies", "/c/cookies.txt", "--sleep-requests", "1.0", "--", url)
        }

    @Test
    fun insideAProxyScopeTheRouteReachesTheCommandAndTheEnvironment() =
        runTest {
            val login = ProxyRoute.Via(ProxyEndpoint(ProxyType.SOCKS5, "gw.example.com", 1080, "ann", "pw"))
            val plain = ProxyRoute.Via(ProxyEndpoint(ProxyType.HTTP, "gw.example.com", 3128))

            for ((route, args, environment) in listOf<Triple<ProxyRoute, List<String>, Map<String, String>>>(
                Triple(ProxyRoute.Direct, listOf("--proxy", ""), mapOf("ALL_PROXY" to "")),
                Triple(plain, listOf("--proxy", "http://gw.example.com:3128"), mapOf("ALL_PROXY" to "http://gw.example.com:3128")),
                Triple(login, emptyList(), mapOf("ALL_PROXY" to "socks5h://ann:pw@gw.example.com:1080")),
            )) {
                val runner = fake()
                withProxy(ProxyRouter { _, _ -> route }, DownloadId(1)) { YtDlpRunner(runner).extract(tool, null, url) }

                val started = runner.started.single()
                started.command shouldBe listOf("/tools/yt-dlp", "--ignore-config", "-J", "--no-playlist") + args + listOf("--", url)
                started.environment.filterKeys { it == "ALL_PROXY" } shouldBe environment
                started.command.joinToString(" ").contains("ann:pw") shouldBe false
            }
        }

    @Test
    fun aBlockedRouteStartsNothing() =
        runTest {
            val runner = fake()

            val extracted =
                withProxy(
                    ProxyRouter { _, _ -> ProxyRoute.Blocked("no password saved") },
                    DownloadId(1),
                ) { YtDlpRunner(runner).extract(tool, null, url) }

            extracted.shouldBeInstanceOf<StepResult.Failed>().errorClass shouldBe ErrorClass.PROXY
            runner.started shouldBe emptyList()
        }

    @Test
    fun aWithheldHintIsReported() =
        runTest {
            for (
            warning in
            listOf(
                "WARNING: [youtube] Some web client https formats have been skipped as they are DRM protected",
                "ERROR: [youtube] abc: Requested format is not available. Use --list-formats",
                "WARNING: Sign in to confirm you're not a bot",
            )
            ) {
                val extracted = YtDlpRunner(fake(stderr = listOf("noise", warning))).extract(tool, null, url)

                extracted.shouldBeInstanceOf<StepResult.Ok<Extracted>>().value shouldBe Extracted(json, hinted = true)
            }
        }

    @Test
    fun noHintOnAQuietRun() =
        runTest {
            val extracted = YtDlpRunner(fake(stderr = listOf("[youtube] abc: Downloading webpage"))).extract(tool, null, url)

            extracted shouldBe StepResult.Ok(Extracted(json, hinted = false))
        }
}
