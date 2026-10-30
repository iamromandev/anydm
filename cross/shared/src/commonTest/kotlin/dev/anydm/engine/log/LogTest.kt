package dev.anydm.engine.log

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import kotlin.test.Test
import kotlin.time.Clock
import kotlin.time.Instant

class LogTest {
    private fun clockAt(text: String) =
        object : Clock {
            override fun now() = Instant.parse(text)
        }

    private val sink = RecordingLogSink()
    private val log = Log(sink, clockAt("2026-10-17T12:34:56.789Z"))

    @Test
    fun aLineHasTheTimeLevelTagAndMessage() {
        log.info("queue") { "job 12 completed" }

        sink.lines shouldBe listOf("2026-10-17T12:34:56.789Z INFO  queue job 12 completed")
    }

    @Test
    fun aWholeSecondStillHasMillis() {
        val sink = RecordingLogSink()
        Log(sink, clockAt("2026-10-17T12:34:56Z")).warn("store") { "x" }

        sink.lines shouldBe listOf("2026-10-17T12:34:56.000Z WARN  store x")
    }

    @Test
    fun levelsBelowTheMinimumAreSkipped() {
        var built = false
        log.debug("job") {
            built = true
            "moved"
        }
        built shouldBe false
        sink.lines shouldBe emptyList()

        log.minLevel = LogLevel.DEBUG
        log.debug("job") { "moved" }

        sink.lines shouldBe listOf("2026-10-17T12:34:56.789Z DEBUG job moved")
    }

    @Test
    fun anErrorCarriesItsTrace() {
        log.error("queue", IllegalStateException("bug")) { "job 1 crashed" }

        sink.lines.size shouldBe 1
        val lines = sink.lines.single().lines()
        lines.first() shouldBe "2026-10-17T12:34:56.789Z ERROR queue job 1 crashed"
        lines[1] shouldStartWith "\tjava.lang.IllegalStateException: bug"
        lines.drop(1).all { it.startsWith("\t") } shouldBe true
    }

    @Test
    fun aStackTraceIsRedacted() {
        log.warn("queue", IllegalStateException("fetch https://ann:pw@h/f")) { "Cookie: a=1" }

        sink.lines.single() shouldNotContain "pw@"
        sink.lines.single() shouldNotContain "a=1"
    }

    @Test
    fun noneDropsEverything() {
        var built = false
        Log.None.error("x") {
            built = true
            "y"
        }

        built shouldBe false
    }
}
