package dev.anydm.engine

import dev.anydm.engine.core.DownloadKind
import dev.anydm.engine.core.ErrorClass
import dev.anydm.engine.log.Log
import dev.anydm.engine.log.RecordingLogSink
import dev.anydm.engine.platform.virtualClock
import dev.anydm.engine.protocol.FakeFailure
import dev.anydm.engine.protocol.FakeProtocol
import dev.anydm.engine.protocol.StepResult
import dev.anydm.engine.store.NewDownload
import dev.anydm.engine.store.StoreTestBase
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes

/** What the engine writes to its log (#556), on a new database from [newDriver] for each test. */
abstract class EngineLogContract : StoreTestBase() {
    private fun TestScope.settle() {
        advanceTimeBy(10.minutes)
        runCurrent()
    }

    private suspend fun TestScope.logged(
        protocol: FakeProtocol = FakeProtocol(),
        debug: Boolean = false,
        run: suspend EngineHarness.() -> Unit,
    ): List<String> {
        val sink = RecordingLogSink()
        val store = newStore()
        if (debug) store.settings.update { it.copy(debugLogging = true) }
        val h = EngineHarness(this, store, protocol, log = Log(sink, virtualClock()))
        h.engine.start()
        h.run()
        settle()
        h.engine.stop()
        return sink.lines
    }

    private fun List<String>.shouldHaveInOrder(vararg parts: String) {
        var from = 0
        for (part in parts) {
            val at = drop(from).indexOfFirst { part in it }
            withClue("'$part' after line $from in $this") { (at >= 0) shouldBe true }
            from += at + 1
        }
    }

    @Test
    fun aRunLogsItsLifecycle() =
        runTest {
            val lines = logged { addJob() }

            lines.shouldHaveInOrder(
                "INFO  engine engine started (0 jobs)",
                "INFO  job job 1 added",
                "INFO  job job 1 started",
                "INFO  job job 1 completed",
                "INFO  engine engine stopped",
            )
        }

    @Test
    fun aFailureIsAWarning() =
        runTest {
            val lines = logged(FakeProtocol(failure = FakeFailure.AtProbe(StepResult.Failed(ErrorClass.AUTH, "denied")))) { addJob() }

            lines.shouldHaveInOrder("WARN  job job 1 failed (AUTH): denied")
        }

    @Test
    fun aCrashLogsItsTrace() =
        runTest {
            val lines = logged(FakeProtocol(failure = FakeFailure.ThrowAtProbe(IllegalStateException("bug")))) { addJob() }

            val crash = lines.single { "ERROR queue job 1 crashed" in it }
            crash shouldContain "\tat "
            crash shouldContain "bug"
        }

    @Test
    fun debugLoggingShowsStatusMoves() =
        runTest {
            logged(debug = true) { addJob() }.shouldHaveInOrder("DEBUG job job 1 QUEUED -> DOWNLOADING")
            logged { addJob() }.none { "DEBUG" in it } shouldBe true
        }

    @Test
    fun aMirrorSecretNeverReachesTheLog() =
        runTest {
            val url = "https://ann:pw@host/f?token=abc"
            val lines =
                logged(FakeProtocol(failure = FakeFailure.AtProbe(StepResult.Failed(null, "cannot fetch $url")))) {
                    engine.add(NewDownload(DownloadKind.DIRECT, mirrors = listOf(url)))
                }

            (lines.isNotEmpty()) shouldBe true
            lines.none { "pw@" in it || "token=abc" in it } shouldBe true
        }
}
