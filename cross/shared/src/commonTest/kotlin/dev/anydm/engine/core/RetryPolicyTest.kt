package dev.anydm.engine.core

import dev.anydm.engine.core.ErrorClass.AUTH
import dev.anydm.engine.core.ErrorClass.DISK_FULL
import dev.anydm.engine.core.ErrorClass.DUPLICATE
import dev.anydm.engine.core.ErrorClass.FORMAT_WITHHELD
import dev.anydm.engine.core.ErrorClass.GONE
import dev.anydm.engine.core.ErrorClass.INTEGRITY
import dev.anydm.engine.core.ErrorClass.PROXY
import dev.anydm.engine.core.ErrorClass.RATE_LIMITED
import dev.anydm.engine.core.ErrorClass.TOOL_MISSING
import dev.anydm.engine.core.ErrorClass.TRANSIENT
import dev.anydm.engine.platform.virtualEpoch
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

class RetryPolicyTest {
    private val t0 = virtualEpoch
    private val m1 = Mirror(MirrorId(11), DownloadId(1), "https://example.com/b", 1)

    private fun policy(random: Double = 0.0) = RetryPolicy(random = FixedRandom(random))

    private fun RetryPolicy.decide(
        errorClass: ErrorClass?,
        attemptCount: Int = 0,
        maxRetries: Int = 5,
        madeProgress: Boolean = false,
        nextMirror: Mirror? = m1,
        retryAfter: Duration? = null,
        integrityBefore: Boolean = false,
        offline: Boolean = false,
    ) = decide(
        job = aPendingDownload().copy(attemptCount = attemptCount),
        failure = StepFailure(Step.TRANSFER, errorClass, "failed", retryAfter),
        madeProgress = madeProgress,
        nextMirror = nextMirror,
        maxRetries = maxRetries,
        now = t0,
        integrityBefore = integrityBefore,
        offline = offline,
    )

    @Test
    fun theFirstIntegrityFailureStartsOver() {
        policy().decide(INTEGRITY, integrityBefore = false) shouldBe FailureAction.StartOver
        policy().decide(INTEGRITY, integrityBefore = true) shouldBe FailureAction.Fail
    }

    @Test
    fun eachErrorClassLeadsToItsAction() {
        val expected =
            mapOf(
                TRANSIENT to FailureAction.Retry(t0 + 2.5.seconds, 1),
                RATE_LIMITED to FailureAction.Retry(t0 + 2.5.seconds, 1),
                GONE to FailureAction.NextMirror(m1),
                DISK_FULL to FailureAction.Pause(PauseReason.LOW_DISK),
                TOOL_MISSING to FailureAction.Pause(PauseReason.TOOLS),
                AUTH to FailureAction.Fail,
                FORMAT_WITHHELD to FailureAction.Fail,
                PROXY to FailureAction.Fail,
                INTEGRITY to FailureAction.StartOver,
                DUPLICATE to FailureAction.Fail,
                null to FailureAction.Fail,
            )
        expected.keys.filterNotNull().toSet() shouldBe ErrorClass.entries.toSet()
        for ((errorClass, action) in expected) {
            withClue(errorClass) { policy().decide(errorClass) shouldBe action }
        }
    }

    @Test
    fun aUsedUpBudgetFails() {
        policy().decide(TRANSIENT, attemptCount = 5) shouldBe FailureAction.Fail
        policy().decide(RATE_LIMITED, attemptCount = 5) shouldBe FailureAction.Fail
    }

    @Test
    fun zeroMaxRetriesNeverRetries() {
        policy().decide(TRANSIENT, maxRetries = 0) shouldBe FailureAction.Fail
    }

    @Test
    fun goneWithNoMirrorLeftFails() {
        policy().decide(GONE, nextMirror = null) shouldBe FailureAction.Fail
    }

    @Test
    fun progressResetsTheCount() {
        policy().decide(TRANSIENT, attemptCount = 5, madeProgress = true) shouldBe FailureAction.Retry(t0 + 2.5.seconds, 1)
    }

    @Test
    fun backoffStaysWithinItsBounds() {
        val full = listOf(5, 10, 20, 40, 80, 160, 300, 300).map { it.seconds }
        for ((i, d) in full.withIndex()) {
            val count = i + 1
            withClue("count $count") {
                policy(0.0).decide(TRANSIENT, attemptCount = count - 1, maxRetries = 10) shouldBe FailureAction.Retry(t0 + d / 2, count)
                val at =
                    policy(
                        0.999999,
                    ).decide(TRANSIENT, attemptCount = count - 1, maxRetries = 10).shouldBeInstanceOf<FailureAction.Retry>().at
                (at <= t0 + d) shouldBe true
                (at > t0 + d * 0.99) shouldBe true
            }
        }
    }

    @Test
    fun aHugeCountStaysAtTheCap() {
        policy().decide(TRANSIENT, attemptCount = 1000, maxRetries = 2000) shouldBe FailureAction.Retry(t0 + 150.seconds, 1001)
    }

    @Test
    fun retryAfterIsHonoredAndCapped() {
        policy().decide(RATE_LIMITED, retryAfter = 30.seconds) shouldBe FailureAction.Retry(t0 + 30.seconds, 1)
        policy().decide(RATE_LIMITED, retryAfter = 2.hours) shouldBe FailureAction.Retry(t0 + 1.hours, 1)
        policy().decide(TRANSIENT, retryAfter = 10.seconds) shouldBe FailureAction.Retry(t0 + 10.seconds, 1)
        policy().decide(RATE_LIMITED) shouldBe FailureAction.Retry(t0 + 2.5.seconds, 1)
    }

    @Test
    fun transientWhileOfflineIsANetworkPause() {
        for (count in listOf(0, 99)) {
            withClue("attemptCount $count") {
                policy().decide(TRANSIENT, attemptCount = count, maxRetries = 5, offline = true) shouldBe
                    FailureAction.Pause(PauseReason.NETWORK)
            }
        }
    }

    @Test
    fun rateLimitedWhileOfflineStillRetries() {
        policy().decide(RATE_LIMITED, offline = true) shouldBe FailureAction.Retry(t0 + 2.5.seconds, 1)
        policy().decide(RATE_LIMITED, attemptCount = 5, offline = true) shouldBe FailureAction.Fail
    }

    @Test
    fun offlineFalseChangesNothing() {
        for (errorClass in ErrorClass.entries + null) {
            withClue(errorClass) { policy().decide(errorClass, offline = false) shouldBe policy().decide(errorClass) }
        }
        policy().decide(TRANSIENT, offline = false) shouldBe FailureAction.Retry(t0 + 2.5.seconds, 1)
    }

    @Test
    fun goneAndAuthIgnoreOffline() {
        policy().decide(GONE, offline = true) shouldBe FailureAction.NextMirror(m1)
        policy().decide(GONE, nextMirror = null, offline = true) shouldBe FailureAction.Fail
        policy().decide(AUTH, offline = true) shouldBe FailureAction.Fail
        for (errorClass in ErrorClass.entries.filter { it != TRANSIENT }) {
            withClue(errorClass) { policy().decide(errorClass, offline = true) shouldBe policy().decide(errorClass) }
        }
    }

    @Test
    fun aBadConfigurationIsRejected() {
        shouldThrow<IllegalArgumentException> { RetryPolicy(base = Duration.ZERO) }
        shouldThrow<IllegalArgumentException> { RetryPolicy(base = 5.seconds, cap = 1.seconds) }
        shouldThrow<IllegalArgumentException> { RetryPolicy(rateLimitCap = Duration.ZERO) }
    }
}
