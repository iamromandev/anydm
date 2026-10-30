package dev.anydm.engine.media

import dev.anydm.engine.core.ErrorClass
import dev.anydm.engine.platform.FakeProcessRunner
import dev.anydm.engine.protocol.StepResult
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

class YtDlpDownloadTest {
    private fun line(
        file: String,
        downloaded: Long,
    ) = """{"status":"downloading","filename":"$file","downloaded_bytes":$downloaded,"total_bytes":5000}"""

    private fun command(limit: Long?) = listOf("yt-dlp", "--limit-rate", limit.toString())

    private fun runner(
        stdout: List<String> = emptyList(),
        stderr: List<String> = emptyList(),
        exit: Int = 0,
        delay: kotlin.time.Duration = kotlin.time.Duration.ZERO,
    ) = FakeProcessRunner(listOf(FakeProcessRunner.Scripted(listOf("yt-dlp"), exit, stdout, stderr, delay)))

    @Test
    fun successReportsMeterAndProgress() =
        runTest {
            val io = ScriptedIo()
            val fake = runner(listOf(line("v", 100), "[download] noise", line("v", 400), line("a", 50)))

            YtDlpDownload(fake).download(::command, io) shouldBe StepResult.Ok(Unit)

            io.meter.bytes shouldBe 450L
            io.limiter.bytes shouldBe 450L
            io.progress.last() shouldBe 450L
            io.progress shouldBe io.progress.sorted()
        }

    @Test
    fun aFailedExitIsClassified() =
        runTest {
            val fake = runner(stderr = listOf("ERROR: [youtube] x: Private video. Sign in"), exit = 1)

            val failed = YtDlpDownload(fake).download(::command, ScriptedIo()).shouldBeInstanceOf<StepResult.Failed>()

            failed.errorClass shouldBe ErrorClass.GONE
        }

    @Test
    fun aMissingBinaryIsToolMissing() =
        runTest {
            val failed =
                YtDlpDownload(
                    FakeProcessRunner(emptyList()),
                ).download(::command, ScriptedIo()).shouldBeInstanceOf<StepResult.Failed>()

            failed.errorClass shouldBe ErrorClass.TOOL_MISSING
            failed.message shouldBe "yt-dlp"
        }

    @Test
    fun aShareChangeRestartsWithTheNewLimit() =
        runTest {
            var share: Long? = 1000
            val io = ScriptedIo { share }
            val fake = runner(listOf(line("v", 100)), delay = 60.seconds)
            launch {
                delay(5.seconds)
                share = 5000
            }

            YtDlpDownload(fake).download(::command, io) shouldBe StepResult.Ok(Unit)

            fake.started.map { it.command[2] } shouldBe listOf("1000", "5000")
            io.meter.bytes shouldBe 100L
            io.limiter.bytes shouldBe 100L
        }

    @Test
    fun aSmallChangeDoesNotRestart() =
        runTest {
            var share: Long? = 1000
            val fake = runner(listOf(line("v", 100)), delay = 30.seconds)
            launch {
                delay(5.seconds)
                share = 1100
            }

            YtDlpDownload(fake).download(::command, ScriptedIo { share }) shouldBe StepResult.Ok(Unit)

            fake.started.size shouldBe 1
        }

    @Test
    fun nullToANumberRestarts() =
        runTest {
            var share: Long? = null
            val fake = runner(listOf(line("v", 100)), delay = 30.seconds)
            launch {
                delay(5.seconds)
                share = 3000
            }

            YtDlpDownload(fake).download(::command, ScriptedIo { share })

            fake.started.map { it.command[2] } shouldBe listOf("null", "3000")
        }

    @Test
    fun restartsAreSpacedByTheMinimumGap() =
        runTest {
            var share: Long? = 1000
            val fake = runner(listOf(line("v", 100)), delay = 30.seconds)
            launch {
                delay(3.seconds)
                share = 2000
                delay(3.seconds)
                share = 4000
            }

            YtDlpDownload(fake).download(::command, ScriptedIo { share })

            fake.started.map { it.command[2] } shouldBe listOf("1000", "4000")
        }

    @Test
    fun aResumedPartialIsNotCountedAgain() =
        runTest {
            val io = ScriptedIo()
            val fake = runner(listOf(line("v", 1200)))

            YtDlpDownload(fake).download(::command, io, seed = mapOf("v" to 1000L)) shouldBe StepResult.Ok(Unit)

            io.meter.bytes shouldBe 200L
            io.limiter.bytes shouldBe 200L
            io.progress.first() shouldBe 1000L
            io.progress.last() shouldBe 1200L
        }
}
