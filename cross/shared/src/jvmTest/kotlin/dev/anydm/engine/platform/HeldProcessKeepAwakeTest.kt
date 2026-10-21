package dev.anydm.engine.platform

import dev.anydm.engine.log.Log
import dev.anydm.engine.log.RecordingLogSink
import dev.anydm.engine.platform.FakeProcessRunner.Scripted
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class HeldProcessKeepAwakeTest {
    private val sink = RecordingLogSink()
    private val noWindows =
        object : ExecutionStateApi {
            override fun set(flags: Int): Int = error("no Windows call in this test")
        }

    private fun TestScope.log() = Log(sink, virtualClock())

    private fun TestScope.keeper(runner: FakeProcessRunner) =
        HeldProcessKeepAwake(runner, ProcessSpec(listOf("helper", "arg")), "helper", log(), backgroundScope)

    private fun runnerOf(
        delay: Duration = Duration.INFINITE,
        exitCode: Int = 0,
    ) = FakeProcessRunner(listOf(Scripted(listOf("helper"), exitCode = exitCode, delay = delay)))

    @Test
    fun theMacOsSpecIsExact() =
        runTest {
            val keep = keepAwakeFor(DesktopOs.MACOS, FakeProcessRunner(emptyList()), 4242, noWindows, log())

            keep.shouldBeInstanceOf<HeldProcessKeepAwake>().spec.command shouldBe listOf("caffeinate", "-i", "-w", "4242")
        }

    @Test
    fun theLinuxSpecIsExact() =
        runTest {
            val keep = keepAwakeFor(DesktopOs.LINUX, FakeProcessRunner(emptyList()), 4242, noWindows, log())

            keep.shouldBeInstanceOf<HeldProcessKeepAwake>().spec.command shouldBe
                listOf(
                    "systemd-inhibit",
                    "--what=idle:sleep",
                    "--who=AnyDM",
                    "--why=Downloading",
                    "--mode=block",
                    "sh",
                    "-c",
                    "while kill -0 4242 2>/dev/null; do sleep 5; done",
                )
        }

    @Test
    fun theLinuxHelperEndsWithTheJvmAndNeverWaitsForever() =
        runTest {
            val keep = keepAwakeFor(DesktopOs.LINUX, FakeProcessRunner(emptyList()), 31337, noWindows, log())

            val command = keep.shouldBeInstanceOf<HeldProcessKeepAwake>().spec.command
            command.any { "kill -0 31337 " in it } shouldBe true
            command.any { "infinity" in it } shouldBe false
        }

    @Test
    fun acquireStartsOneProcessHoweverOftenCalled() =
        runTest {
            val runner = runnerOf()
            val keep = keeper(runner)

            keep.acquire()
            keep.acquire()
            keep.acquire()
            runCurrent()

            runner.started.size shouldBe 1
        }

    @Test
    fun releaseStopsIt() =
        runTest {
            val runner = runnerOf()
            val keep = keeper(runner)
            keep.acquire()

            keep.release()
            keep.release()

            runner.stopped.size shouldBe 1
            sink.lines shouldBe emptyList()
        }

    @Test
    fun releaseBeforeAcquireIsANoOp() =
        runTest {
            val runner = runnerOf()

            keeper(runner).release()

            runner.started shouldBe emptyList()
            runner.stopped shouldBe emptyList()
        }

    @Test
    fun acquireAfterReleaseStartsAgain() =
        runTest {
            val runner = runnerOf()
            val keep = keeper(runner)

            keep.acquire()
            keep.release()
            keep.acquire()

            runner.started.size shouldBe 2
        }

    @Test
    fun aMissingCommandWarnsOnceAndNeverThrows() =
        runTest {
            val keep = keeper(FakeProcessRunner(emptyList()))

            keep.acquire()
            keep.acquire()
            keep.release()

            sink.lines.size shouldBe 1
            sink.lines.single().contains("WARN") shouldBe true
            sink.lines.single().contains("keep awake unavailable: helper") shouldBe true
        }

    @Test
    fun aThrowingRunnerWarnsOnceAndNeverThrows() =
        runTest {
            val throwing =
                object : ProcessRunner {
                    override suspend fun run(spec: ProcessSpec): ProcessOutcome<ProcessResult> = error("not used")

                    override suspend fun start(spec: ProcessSpec): ProcessOutcome<RunningProcess> = error("boom")
                }
            val keep = HeldProcessKeepAwake(throwing, ProcessSpec(listOf("helper")), "helper", log(), backgroundScope)

            keep.acquire()
            keep.acquire()

            sink.lines.size shouldBe 1
        }

    @Test
    fun aHelperThatExitsEarlyIsStartedAgain() =
        runTest {
            val runner = runnerOf(delay = 1.seconds, exitCode = 1)
            val keep = keeper(runner)
            keep.acquire()

            advanceTimeBy(2.seconds)
            runCurrent()
            keep.acquire()

            runner.started.size shouldBe 2
        }

    @Test
    fun aLiveHelperIsNotStartedAgain() =
        runTest {
            val runner = runnerOf(delay = 10.seconds)
            val keep = keeper(runner)
            keep.acquire()

            advanceTimeBy(5.seconds)
            runCurrent()
            keep.acquire()

            runner.started.size shouldBe 1
        }
}
