package dev.anydm.engine.core

import dev.anydm.engine.protocol.Limiter
import dev.anydm.engine.protocol.Meter
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class StepRunnerShareTest {
    @Test
    fun theRunnerHandsTheShareToTheProtocol() =
        runTest {
            val h = StepHarness()
            var seen: Long? = -1
            h.protocol.onTransfer = { seen = it.speedShare() }
            val ready = h.prepare().shouldBeInstanceOf<PrepareOutcome.Ready>()

            h.runner.run(ready, h.listener, Limiter.None, Meter.None) { 777L }

            seen shouldBe 777L
        }

    @Test
    fun withoutAShareThereIsNone() =
        runTest {
            val h = StepHarness()
            var seen: Long? = -1
            h.protocol.onTransfer = { seen = it.speedShare() }

            h.runAll()

            seen shouldBe null
        }
}
