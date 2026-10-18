package dev.anydm.engine.e2eharness

import dev.anydm.engine.e2e.EngineProcessRunner
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

    @Test
    fun argumentsWithSpacesArriveIntact() {
        val child = start("echo", "/tmp/a b/c d.db")
        child.awaitDone(10.seconds)
        child.lines shouldContain "arg /tmp/a b/c d.db"
    }
}
