package dev.anydm.engine.protocol

import dev.anydm.engine.core.ErrorClass
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

class StepResultTest {
    @Test
    fun retryAfterIsOnlyForTransientAndRateLimited() {
        StepResult.Failed(ErrorClass.TRANSIENT, "x", 1.seconds).retryAfter shouldBe 1.seconds
        StepResult.Failed(ErrorClass.RATE_LIMITED, "x", 0.seconds).retryAfter shouldBe 0.seconds
        shouldThrow<IllegalArgumentException> { StepResult.Failed(ErrorClass.GONE, "x", 1.seconds) }
        shouldThrow<IllegalArgumentException> { StepResult.Failed(null, "x", 1.seconds) }
    }

    @Test
    fun retryAfterMustNotBeNegative() {
        shouldThrow<IllegalArgumentException> { StepResult.Failed(ErrorClass.TRANSIENT, "x", (-1).seconds) }
    }
}
