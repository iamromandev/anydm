package dev.anydm.engine.net

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** What limits a download's speed most (#634); a small code the UI words. A drive that writes slowly is not measured. */
sealed interface SpeedLimit {
    /** Slow mode caps the speed; a fix: turn it off in Limits. */
    data object SlowMode : SpeedLimit

    /** A speed limit (global, the download's, or a rule's) caps the speed; a fix: change it in Limits. */
    data object SpeedCap : SpeedLimit

    /** The network with [key], called [network], reached its data limit; a fix: change the limit in Limits on that network. */
    data class DataLimit(
        val key: String,
        val network: String,
    ) : SpeedLimit

    /** [network] failed to reach the server a moment ago and is left out. */
    data class Unreachable(
        val network: String,
    ) : SpeedLimit

    /** Whether the UI can offer the Limits dialog as the way to change it. */
    val fixable: Boolean get() = this is SlowMode || this is SpeedCap || this is DataLimit
}

/** Where the cap that all downloads share comes from. */
enum class CapSource { SPEED_LIMIT, SLOW_MODE, DATA_LIMIT }

/**
 * Where the shared cap (the lower of [globalLimit] and the rules' [RuleStatus.downloadCap]) comes from. A user's limit that
 * is lower than the rules' cap is a speed limit; otherwise the rules' cap comes from the slow action of the allowance, of
 * slow mode, or of something else (a schedule, a metered network), which reads as a speed limit.
 */
fun capSourceOf(
    status: RuleStatus,
    globalLimit: Long?,
): CapSource {
    val rules = status.downloadCap ?: return CapSource.SPEED_LIMIT
    if (globalLimit != null && globalLimit < rules) return CapSource.SPEED_LIMIT
    // A rules' cap that is not the slow mode limit is a network's own speed limit.
    if (rules != status.slowMode.limit) return CapSource.SPEED_LIMIT
    return when {
        RuleSource.ALLOWANCE in status.because -> CapSource.DATA_LIMIT
        RuleSource.SLOW_MODE in status.because -> CapSource.SLOW_MODE
        else -> CapSource.SPEED_LIMIT
    }
}

/**
 * What is known about one running download. [speed] and [peak] are its own (bytes per second now, and the highest of its
 * recent seconds in this run); [totalSpeed] is every download together. A [sharedCap] (global limit or rules, from
 * [sharedSource]) bounds [totalSpeed]; [ownCap] is the download's own limit and bounds [speed]. [dataLimit] and
 * [unreachable] name a network the download uses.
 */
data class LimitInputs(
    val speed: Long,
    val peak: Long,
    val totalSpeed: Long,
    val sharedCap: Long? = null,
    val sharedSource: CapSource = CapSource.SPEED_LIMIT,
    val ownCap: Long? = null,
    val dataLimit: SpeedLimit.DataLimit? = null,
    val unreachable: String? = null,
)

/**
 * The most useful reason for [i], or null when nothing limits it. A network problem counts only while the download runs
 * clearly below its recent peak; a data limit counts then too, or when all downloads run at a cap that the allowance sets;
 * another cap counts when it is nearly reached (all together for a shared cap, the download alone for its own).
 * Priority: unreachable network, data limit, slow mode, speed limit.
 */
fun limitOf(i: LimitInputs): SpeedLimit? {
    val belowPeak = i.peak > 0 && i.speed * 10 < i.peak * 7
    val atShared = i.sharedCap != null && i.totalSpeed * 10 >= i.sharedCap * 8
    val atOwn = i.ownCap != null && i.speed * 10 >= i.ownCap * 8
    return when {
        belowPeak && i.unreachable != null -> SpeedLimit.Unreachable(i.unreachable)
        i.dataLimit != null && (belowPeak || (atShared && i.sharedSource == CapSource.DATA_LIMIT)) -> i.dataLimit
        atShared && i.sharedSource == CapSource.SLOW_MODE -> SpeedLimit.SlowMode
        atShared || atOwn -> SpeedLimit.SpeedCap
        else -> null
    }
}

/**
 * Keeps one download's reason steady: a reason that goes is still shown for [hold], and a different one replaces what is
 * shown only after the shown one has stood for [hold], so the line does not flicker.
 */
class LimitHold(
    private val time: TimeSource,
    private val hold: Duration = HOLD,
) {
    private var shown: SpeedLimit? = null
    private var shownAt = time.markNow()
    private var seenAt = time.markNow()

    /** Takes this second's [raw] reason and returns the one to show. */
    fun update(raw: SpeedLimit?): SpeedLimit? {
        // [shown] is what the UI sees; [shownAt] is when it began to show, [seenAt] when [raw] last matched it.
        val current = shown
        when {
            raw == null -> {
                if (current != null && seenAt.elapsedNow() >= hold) shown = null
            }

            raw == current -> {
                seenAt = time.markNow()
            }

            current == null || shownAt.elapsedNow() >= hold -> {
                shown = raw
                shownAt = time.markNow()
                seenAt = shownAt
            }
        }
        return shown
    }

    companion object {
        val HOLD = 5.seconds
    }
}
