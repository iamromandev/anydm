package dev.anydm.engine.media

import dev.anydm.engine.core.CookieSource
import dev.anydm.engine.core.ErrorClass
import dev.anydm.engine.core.MediaSettings
import dev.anydm.engine.core.Pacing
import dev.anydm.engine.core.PacingPreset
import dev.anydm.engine.platform.FakeProcessRunner
import dev.anydm.engine.protocol.StepResult
import dev.anydm.engine.tools.ResolvedTool
import dev.anydm.engine.tools.Tool
import dev.anydm.engine.tools.ToolVersion
import io.kotest.matchers.collections.shouldContainInOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import kotlin.test.Test

class YtDlpPlaylistSourceTest {
    private val url = "https://www.youtube.com/playlist?list=PL1"
    private var available = setOf(Tool.YT_DLP)
    private var settings = MediaSettings()

    private fun tool(tool: Tool) =
        ResolvedTool(tool, ToolVersion.parse("2026.1.1")!!, tool.executables.associateWith { Path("/tools/$it") }, managed = true)

    private fun yt(
        vararg stdout: String,
        stderr: List<String> = emptyList(),
        exit: Int = 0,
    ) = FakeProcessRunner(listOf(FakeProcessRunner.Scripted(listOf("/tools/yt-dlp"), exit, stdout.toList(), stderr)))

    private fun source(runner: FakeProcessRunner) = YtDlpPlaylistSource(runner, { if (it in available) tool(it) else null }, { settings })

    @Test
    fun theCommandHasFlatPlaylistAndTheSeparator() =
        runBlocking<Unit> {
            val runner = yt(MediaFixtures.flatPlaylistJson())

            val parsed = source(runner).list(url).shouldBeInstanceOf<StepResult.Ok<PlaylistParse>>().value

            parsed
                .shouldBeInstanceOf<PlaylistParse.Ok>()
                .listing.entries.size shouldBe 3
            val command = runner.started.single().command
            command.take(4) shouldBe listOf("/tools/yt-dlp", "--ignore-config", "-J", "--flat-playlist")
            command.takeLast(2) shouldBe listOf("--", url)
            command.contains("--no-playlist") shouldBe false
        }

    @Test
    fun cookiesAndPacingFlagsAreAdded() =
        runBlocking<Unit> {
            settings = MediaSettings(CookieSource.File("/home/u/cookies.txt"), Pacing(PacingPreset.BALANCED))
            val runner = yt(MediaFixtures.flatPlaylistJson())

            source(runner).list(url)

            val command = runner.started.single().command
            command.shouldContainInOrder("--cookies", "/home/u/cookies.txt")
            command.contains("--sleep-requests") shouldBe true
        }

    @Test
    fun aMissingYtDlpIsToolMissing() =
        runBlocking<Unit> {
            available = emptySet()

            val failed = source(yt()).list(url).shouldBeInstanceOf<StepResult.Failed>()

            failed.errorClass shouldBe ErrorClass.TOOL_MISSING
            failed.message shouldBe "yt-dlp"
        }

    @Test
    fun aFailureIsClassified() =
        runBlocking<Unit> {
            val failed = source(yt(stderr = listOf("ERROR: [youtube:tab] PL1: This playlist does not exist"), exit = 1)).list(url)

            failed.shouldBeInstanceOf<StepResult.Failed>().errorClass shouldBe ErrorClass.GONE
        }

    @Test
    fun noJsonIsAFailure() =
        runBlocking<Unit> {
            source(yt()).list(url).shouldBeInstanceOf<StepResult.Failed>()
        }
}
