package dev.anydm.engine.platform

import dev.anydm.engine.log.Log
import dev.anydm.engine.log.RecordingLogSink
import dev.anydm.engine.platform.FakeProcessRunner.Scripted
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/**
 * SAFETY: these commands put the machine to sleep or shut it down. The helper below takes only a [FakeProcessRunner],
 * so a test here cannot run one for real. Never pass a DesktopProcessRunner to [powerActionsFor] in a test.
 */
class DesktopPowerActionsTest {
    private val sink = RecordingLogSink()

    private fun TestScope.actions(
        osName: String,
        runner: FakeProcessRunner,
    ): PowerActions = powerActionsFor(DesktopOs.of(osName), runner, Log(sink, virtualClock()))

    private fun anyProgram(exitCode: Int = 0) = FakeProcessRunner(listOf(Scripted(emptyList(), exitCode = exitCode)))

    @Test
    fun eachCommandLineIsExact() =
        runTest {
            val cases =
                listOf(
                    Triple("Mac OS X", true, listOf("pmset", "sleepnow")),
                    Triple("Mac OS X", false, listOf("osascript", "-e", "tell application \"System Events\" to shut down")),
                    Triple("Windows 11", true, listOf("rundll32.exe", "powrprof.dll,SetSuspendState", "0,1,0")),
                    Triple("Windows 11", false, listOf("shutdown", "/s", "/t", "0")),
                    Triple("Linux", true, listOf("systemctl", "suspend")),
                    Triple("Linux", false, listOf("systemctl", "poweroff")),
                )
            for ((os, sleep, command) in cases) {
                val runner = anyProgram()
                val power = actions(os, runner)

                (if (sleep) power.sleep() else power.shutDown()) shouldBe true

                runner.started.map { it.command } shouldBe listOf(command)
            }
        }

    @Test
    fun exitZeroIsTrue() =
        runTest {
            actions("Linux", anyProgram(0)).sleep() shouldBe true
            actions("Linux", anyProgram(0)).shutDown() shouldBe true
            sink.lines shouldBe emptyList()
        }

    @Test
    fun nonZeroOrMissingIsFalse() =
        runTest {
            actions("Linux", anyProgram(1)).sleep() shouldBe false
            actions("Linux", anyProgram(1)).shutDown() shouldBe false
            actions("Mac OS X", FakeProcessRunner(emptyList())).sleep() shouldBe false
            actions("Windows 11", FakeProcessRunner(emptyList())).shutDown() shouldBe false

            sink.lines.size shouldBe 4
            sink.lines.all { it.contains("WARN") && it.contains("platform") } shouldBe true
        }

    @Test
    fun aThrowingRunnerIsFalse() =
        runTest {
            val throwing =
                object : ProcessRunner {
                    override suspend fun run(spec: ProcessSpec): ProcessOutcome<ProcessResult> = error("boom")

                    override suspend fun start(spec: ProcessSpec): ProcessOutcome<RunningProcess> = error("boom")
                }

            powerActionsFor(DesktopOs.LINUX, throwing, Log(sink, virtualClock())).sleep() shouldBe false
        }
}
