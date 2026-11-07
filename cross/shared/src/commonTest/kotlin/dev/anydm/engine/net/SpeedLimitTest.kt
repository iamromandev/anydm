package dev.anydm.engine.net

import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

/** What limits a download's speed (#634): the pick by priority, and the hold that keeps the line steady. */
class SpeedLimitTest {
    /** A download running at 100 of a recent peak of 1000, so it is clearly below its peak, with nothing else set. */
    private val slow = LimitInputs(speed = 100, peak = 1_000, totalSpeed = 100)

    private val wifi = SpeedLimit.DataLimit("en0", "Wi-Fi")

    @Test
    fun nothingLimitsAnUnlimitedDownloadRunningAtItsPeak() {
        limitOf(LimitInputs(speed = 1_000, peak = 1_000, totalSpeed = 1_000)) shouldBe null
    }

    @Test
    fun nothingLimitsASlowDownloadWhenNothingIsSet() {
        limitOf(slow) shouldBe null
    }

    @Test
    fun anUnreachableNetworkBeatsEveryOtherReason() {
        val all =
            slow.copy(sharedCap = 100, sharedSource = CapSource.SLOW_MODE, ownCap = 100, dataLimit = wifi, unreachable = "iPhone")
        limitOf(all) shouldBe SpeedLimit.Unreachable("iPhone")
    }

    @Test
    fun aReachedDataLimitBeatsSlowModeAndTheCapsWhileBelowThePeak() {
        limitOf(slow.copy(sharedCap = 100, sharedSource = CapSource.SLOW_MODE, dataLimit = wifi)) shouldBe wifi
    }

    @Test
    fun aDataLimitWhoseSlowActionCapsTheSpeedIsNamedInSteadyState() {
        // At the cap and at its peak: not below it, yet the reason is still the data limit, not slow mode or a speed limit.
        val steady =
            LimitInputs(speed = 500, peak = 500, totalSpeed = 480, sharedCap = 500, sharedSource = CapSource.DATA_LIMIT, dataLimit = wifi)
        limitOf(steady) shouldBe wifi
    }

    @Test
    fun aDataLimitThatCapsNothingIsNotNamedAtThePeak() {
        limitOf(LimitInputs(speed = 500, peak = 500, totalSpeed = 500, dataLimit = wifi)) shouldBe null
    }

    @Test
    fun slowModeBeatsASpeedLimit() {
        limitOf(slow.copy(sharedCap = 100, sharedSource = CapSource.SLOW_MODE, ownCap = 100)) shouldBe SpeedLimit.SlowMode
    }

    @Test
    fun aSharedCapIsNamedWhenAllTogetherRunAtItEvenAtThePeak() {
        limitOf(LimitInputs(speed = 500, peak = 500, totalSpeed = 950, sharedCap = 1_000)) shouldBe SpeedLimit.SpeedCap
    }

    @Test
    fun aCapFarAboveTheSpeedIsNotTheReason() {
        limitOf(LimitInputs(speed = 100, peak = 100, totalSpeed = 100, sharedCap = 1_000, ownCap = 1_000)) shouldBe null
    }

    @Test
    fun aDownloadsOwnLimitIsComparedWithItsOwnSpeedNotTheTotal() {
        // Others carry the total to the cap's height; this download runs far below its own limit.
        limitOf(LimitInputs(speed = 100, peak = 100, totalSpeed = 5_000, ownCap = 1_000)) shouldBe null
        limitOf(LimitInputs(speed = 900, peak = 900, totalSpeed = 900, ownCap = 1_000)) shouldBe SpeedLimit.SpeedCap
    }

    @Test
    fun aNetworkProblemNeedsTheDownloadToBeBelowItsPeak() {
        limitOf(LimitInputs(speed = 900, peak = 1_000, totalSpeed = 900, unreachable = "iPhone", dataLimit = wifi)) shouldBe null
    }

    private fun status(
        cap: Long?,
        because: Set<RuleSource>,
    ) = RuleStatus.None.copy(downloadCap = cap, because = because, slowMode = SlowMode(true, 100))

    @Test
    fun theCapOfAnOverAllowanceComesFromTheDataLimit() {
        capSourceOf(status(100, setOf(RuleSource.ALLOWANCE)), null) shouldBe CapSource.DATA_LIMIT
        capSourceOf(status(100, setOf(RuleSource.ALLOWANCE, RuleSource.SLOW_MODE)), null) shouldBe CapSource.DATA_LIMIT
    }

    @Test
    fun theCapOfSlowModeComesFromSlowMode() {
        capSourceOf(status(100, setOf(RuleSource.SLOW_MODE)), null) shouldBe CapSource.SLOW_MODE
    }

    @Test
    fun aGlobalLimitLowerThanTheSlowCapIsASpeedLimit() {
        capSourceOf(status(100, setOf(RuleSource.SLOW_MODE)), 50) shouldBe CapSource.SPEED_LIMIT
        capSourceOf(status(100, setOf(RuleSource.SLOW_MODE)), 500) shouldBe CapSource.SLOW_MODE
    }

    @Test
    fun aCapThatIsNotTheSlowLimitOrFromAScheduleIsASpeedLimit() {
        capSourceOf(status(70, setOf(RuleSource.SLOW_MODE)), null) shouldBe CapSource.SPEED_LIMIT
        capSourceOf(status(100, setOf(RuleSource.SCHEDULE)), null) shouldBe CapSource.SPEED_LIMIT
        capSourceOf(status(null, emptySet()), 50) shouldBe CapSource.SPEED_LIMIT
    }

    @Test
    fun aReasonIsKeptForFiveSecondsAfterItGoes() {
        val time = TestTimeSource()
        val hold = LimitHold(time)
        hold.update(SpeedLimit.SlowMode) shouldBe SpeedLimit.SlowMode

        time += 4.seconds
        hold.update(null) shouldBe SpeedLimit.SlowMode

        time += 2.seconds
        hold.update(null) shouldBe null
    }

    @Test
    fun aReasonThatKeepsComingIsKeptPastFiveSeconds() {
        val time = TestTimeSource()
        val hold = LimitHold(time)
        repeat(10) {
            hold.update(SpeedLimit.SlowMode) shouldBe SpeedLimit.SlowMode
            time += 1.seconds
        }
        time += 3.seconds
        hold.update(null) shouldBe SpeedLimit.SlowMode
    }

    @Test
    fun aDifferentReasonWaitsForTheHoldToEnd() {
        val time = TestTimeSource()
        val hold = LimitHold(time)
        hold.update(SpeedLimit.SlowMode)

        time += 2.seconds
        hold.update(SpeedLimit.SpeedCap) shouldBe SpeedLimit.SlowMode

        time += 4.seconds
        hold.update(SpeedLimit.SpeedCap) shouldBe SpeedLimit.SpeedCap
    }

    @Test
    fun nothingIsShownBeforeAnyReason() {
        LimitHold(TestTimeSource()).update(null) shouldBe null
    }
}
