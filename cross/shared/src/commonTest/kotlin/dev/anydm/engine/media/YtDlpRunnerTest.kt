package dev.anydm.engine.media

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
