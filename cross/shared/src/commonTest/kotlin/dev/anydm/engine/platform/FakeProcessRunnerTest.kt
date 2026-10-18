package dev.anydm.engine.platform

import dev.anydm.engine.platform.FakeProcessRunner.Scripted
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

class FakeProcessRunnerTest {
    private fun spec(vararg command: String) = ProcessSpec(command.toList())

    private suspend fun FakeProcessRunner.out(vararg command: String): String = (run(spec(*command)) as ProcessOutcome.Ok).value.stdout

    @Test
    fun matchesTheFirstScriptWithAPrefix() =
        runTest {
            val runner =
                FakeProcessRunner(
                    listOf(Scripted(listOf("tool", "--version"), stdout = listOf("1.0")), Scripted(listOf("tool"), stdout = listOf("any"))),
                )

            runner.out("tool", "--version") shouldBe "1.0\n"
            runner.out("tool", "other") shouldBe "any\n"
        }

    @Test
    fun anUnscriptedProgramIsNotFound() =
        runTest {
            FakeProcessRunner(emptyList()).run(spec("nope")) shouldBe ProcessOutcome.Failed(ProcessFailure.NotFound("nope"))
        }

    @Test
    fun recordsEverySpec() =
        runTest {
            val runner = FakeProcessRunner(listOf(Scripted(listOf("a"))))
            runner.run(spec("a", "1"))
            runner.start(spec("a", "2"))

            runner.started.map { it.command } shouldBe listOf(listOf("a", "1"), listOf("a", "2"))
        }

    @Test
    fun runReportsTheExitCodeAndBothStreams() =
        runTest {
            val runner = FakeProcessRunner(listOf(Scripted(listOf("a"), exitCode = 3, stdout = listOf("o1", "o2"), stderr = listOf("e"))))

            (runner.run(spec("a")) as ProcessOutcome.Ok).value shouldBe ProcessResult(3, "o1\no2\n", "e\n", truncated = false)
        }

    @Test
    fun delaysUseVirtualTime() =
        runTest {
            val runner = FakeProcessRunner(listOf(Scripted(listOf("a"), delay = 5.seconds)))
            val result = async { runner.run(spec("a")) }

            advanceTimeBy(4.seconds)
            runCurrent()
            result.isCompleted shouldBe false

            advanceTimeBy(1.seconds)
            runCurrent()
            result.isCompleted shouldBe true
        }

    @Test
    fun startStreamsTheLinesThenGivesTheExitCode() =
        runTest {
            val runner = FakeProcessRunner(listOf(Scripted(listOf("a"), exitCode = 2, stdout = listOf("x"), stderr = listOf("y"))))
            val process = (runner.start(spec("a")) as ProcessOutcome.Ok).value

            process.lines.toList() shouldBe listOf(ProcessLine(ProcessStream.STDOUT, "x"), ProcessLine(ProcessStream.STDERR, "y"))
            process.await() shouldBe 2
        }

    @Test
    fun stopEndsAStreamingProcessWithoutWaitingForItsDelay() =
        runTest {
            val runner = FakeProcessRunner(listOf(Scripted(listOf("a"), delay = 60.seconds)))
            val process = (runner.start(spec("a")) as ProcessOutcome.Ok).value
            val code = async { process.await() }
            runCurrent()

            process.stop()
            runCurrent()

            code.await() shouldBe FakeProcessRunner.STOPPED
            process.shouldBeInstanceOf<RunningProcess>()
        }
}
