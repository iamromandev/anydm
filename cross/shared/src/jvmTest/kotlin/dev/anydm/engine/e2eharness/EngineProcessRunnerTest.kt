package dev.anydm.engine.e2eharness

import dev.anydm.engine.e2e.EngineProcessRunner
import dev.anydm.engine.e2e.withCaseClue
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

/** The child-process harness of the end-to-end tests (#576), with a fake child. */
class EngineProcessRunnerTest {
    private val stderr = File.createTempFile("child-", ".err")
    private val started = mutableListOf<EngineProcessRunner>()

    @AfterTest
    fun cleanUp() {
        started.forEach { it.destroy() }
        stderr.delete()
    }

    private fun start(vararg args: String) =
        EngineProcessRunner.start(FakeChild::class.java.name, args.toList(), stderr).also { started += it }

    @Test
    fun awaitDoneReturnsOnDone() {
        val child = start("count")
        child.awaitDone(10.seconds)
        child.progress shouldBe 20L
    }

    @Test
    fun killAtKillsOnceProgressPasses() {
        val child = start("hang-after-count")
        child.killAt(15, 10.seconds) shouldBe true
        child.isAlive shouldBe false
        child.progress shouldBe 20L
    }

    @Test
    fun aFailedChildSurfacesItsStderr() {
        val child = start("fail")
        shouldThrow<AssertionError> { child.awaitDone(10.seconds) }.message shouldContain "boom"
    }

    @Test
    fun aTimeoutKillsTheChild() {
        val child = start("hang")
        shouldThrow<AssertionError> { child.awaitDone(1.seconds) }
        child.isAlive shouldBe false
    }

    // Final review: a stall or a failure while waiting to kill must not pass as a kill.
    @Test
    fun killAtThrowsWhenTheChildStalls() {
        val child = start("hang")
        shouldThrow<AssertionError> { child.killAt(5, 1.seconds) }
        child.isAlive shouldBe false
    }

    @Test
    fun killAtThrowsWhenTheChildFails() {
        val child = start("fail-after-progress")
        shouldThrow<AssertionError> { child.killAt(10, 10.seconds) }.message shouldContain "broken source"
    }

    // Final review: killAt(0) killed the JVM before it started; it waits for the child's first line.
    @Test
    fun killAtWaitsForTheFirstLine() {
        val child = start("hang-after-count")
        child.killAt(0, 10.seconds) shouldBe true
        child.progress shouldBe 10L
    }

    // Final review: a failed run inside a case must still say which case it was.
    @Test
    fun aCaseClueWrapsAssertionErrors() {
        shouldThrow<AssertionError> { withCaseClue("seed 1 case 2") { throw AssertionError("child timed out") } }.message shouldBe
            "seed 1 case 2: child timed out"
    }

    @Test
    fun argumentsWithSpacesArriveIntact() {
        val child = start("echo", "/tmp/a b/c d.db")
        child.awaitDone(10.seconds)
        child.lines shouldContain "arg /tmp/a b/c d.db"
    }
}
