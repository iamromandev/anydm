package dev.anydm.engine.platform

import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeFalse
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

class DesktopProcessRunnerTest {
    private val runner = DesktopProcessRunner()
    private val folder = Files.createTempDirectory("anydm-process-test-")

    @BeforeTest
    fun notOnWindows() = assumeFalse(System.getProperty("os.name").startsWith("Windows"))

    @AfterTest
    fun cleanUp() {
        folder.toFile().deleteRecursively()
    }

    private fun sh(script: String) = ProcessSpec(listOf("sh", "-c", script))

    private suspend fun gone(pid: Long) {
        withTimeout(3_000) { while (ProcessHandle.of(pid).map { it.isAlive }.orElse(false)) delay(50) }
    }

    @Test
    fun runCollectsOutputAndExitCode() {
        runBlocking {
            val result = (runner.run(sh("echo out; echo err >&2; exit 3")) as ProcessOutcome.Ok).value

            result shouldBe ProcessResult(3, "out\n", "err\n", truncated = false)
        }
    }

    @Test
    fun aMissingProgramIsNotFound() {
        runBlocking {
            runner.run(ProcessSpec(listOf("anydm-no-such-program"))) shouldBe
                ProcessOutcome.Failed(ProcessFailure.NotFound("anydm-no-such-program"))
        }
    }

    @Test
    fun theWorkingDirectoryAndEnvironmentApply() {
        runBlocking {
            val spec =
                ProcessSpec(
                    listOf("sh", "-c", "pwd; echo \$ANYDM_TEST"),
                    kotlinx.io.files.Path(folder.toRealPath().toString()),
                    mapOf("ANYDM_TEST" to "yes"),
                )

            (runner.run(spec) as ProcessOutcome.Ok).value.stdout shouldBe "${folder.toRealPath()}\nyes\n"
        }
    }

    @Test
    fun startStreamsLinesWithTheirStream() {
        runBlocking {
            val process = (runner.start(sh("echo out; echo err >&2")) as ProcessOutcome.Ok).value

            process.lines.toList() shouldContainExactlyInAnyOrder
                listOf(ProcessLine(ProcessStream.STDOUT, "out"), ProcessLine(ProcessStream.STDERR, "err"))
            process.await() shouldBe 0
        }
    }

    @Test
    fun runCapsItsOutput() {
        runBlocking {
            val result = (runner.run(sh("head -c 9437184 /dev/zero | tr '\\0' a")) as ProcessOutcome.Ok).value

            result.truncated shouldBe true
            result.stdout.length shouldBeLessThanOrEqualTo 8 * 1024 * 1024
        }
    }

    @Test
    fun stopKillsTheWholeTree() {
        runBlocking {
            val process = (runner.start(sh("sleep 30 & echo \$!; wait")) as ProcessOutcome.Ok).value
            val pid =
                process.lines
                    .first { it.stream == ProcessStream.STDOUT }
                    .text
                    .trim()
                    .toLong()
            ProcessHandle.of(pid).isPresent shouldBe true

            process.stop()

            gone(pid)
        }
    }

    @Test
    fun cancellingRunStopsTheProcess() {
        runBlocking {
            val pidFile = folder.resolve("pid")
            val job = launch(Dispatchers.Default, CoroutineStart.UNDISPATCHED) { runner.run(sh("sleep 30 & echo \$! > $pidFile; wait")) }
            withTimeout(3_000) { while (!Files.exists(pidFile) || Files.size(pidFile) == 0L) delay(20) }
            val pid = Files.readString(pidFile).trim().toLong()

            job.cancelAndJoin()

            gone(pid)
        }
    }

    @Test
    fun anExitedProcessCanBeStoppedAgain() {
        runBlocking {
            val process = (runner.start(ProcessSpec(listOf("true"))) as ProcessOutcome.Ok).value
            process.await() shouldBe 0

            process.stop()
            process.stop()

            process.shouldBeInstanceOf<RunningProcess>()
        }
    }
}
