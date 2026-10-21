package dev.anydm.engine.platform

import dev.anydm.engine.log.Log
import dev.anydm.engine.net.DefaultRouteNetwork
import dev.anydm.engine.net.DefaultRouteProbe
import dev.anydm.engine.net.WakeDetector
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Checks on a real Mac (#543). Opt-in: every test is skipped unless `ANYDM_MANUAL=1` is in the environment on a Mac.
 * Real processes and sockets are used, but never a sleep, shut-down or restart command: those are checked by command
 * line only, through `DesktopPowerActionsTest`.
 */
class MacManualCheck {
    private fun optIn() {
        assumeTrue(
            "set ANYDM_MANUAL=1 on a Mac to run this",
            System.getenv("ANYDM_MANUAL") == "1" && DesktopOs.of(System.getProperty("os.name")) == DesktopOs.MACOS,
        )
    }

    private fun shell(vararg command: String): Pair<Int, String> {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        val text = process.inputStream.bufferedReader().readText()
        return process.waitFor() to text
    }

    @Test
    fun keepAwakeHoldsCaffeinateAndReleasesIt() {
        optIn()
        runBlocking {
            val keep = keepAwakeForCurrentOs(DesktopProcessRunner(), Log.None)
            val pid = ProcessHandle.current().pid()
            try {
                keep.acquire()
                delay(1.seconds)
                val (_, assertions) = shell("pmset", "-g", "assertions")
                println("MANUAL pmset -g assertions (caffeinate lines): " + assertions.lines().filter { "caffeinate" in it })
                assertions shouldContain "caffeinate"
            } finally {
                keep.release()
            }
            delay(1.seconds)
            val (code, found) = shell("pgrep", "-f", "caffeinate -i -w $pid")
            println("MANUAL pgrep after release: exit $code, output '${found.trim()}'")
            code shouldBe 1
        }
    }

    /** One real default-route probe and lookup, with no change of network needed. */
    @Test
    fun routeProbeAnswersOnce() {
        optIn()
        val online = DefaultRouteProbe().isOnline()
        val net = DefaultRouteNetwork().fresh()
        println("MANUAL DefaultRouteProbe.isOnline() = $online, DefaultRouteNetwork.fresh() = $net")
    }

    /** Needs a human: turn Wi-Fi off and on during the 90 seconds. */
    @Test
    fun routeProbeFollowsWifi() {
        optIn()
        runBlocking {
            val probe = DefaultRouteProbe()
            val network = DefaultRouteNetwork()
            val seen = mutableSetOf<Boolean>()
            var last: Pair<Boolean, String>? = null
            val start = TimeSource.Monotonic.markNow()
            while (start.elapsedNow() < 90.seconds) {
                val now = probe.isOnline() to network.fresh()
                if (now != last) println("MANUAL route: online=${now.first} interface=${now.second}")
                last = now
                seen += now.first
                delay(1.seconds)
            }
            seen shouldBe setOf(true, false)
        }
    }

    /** Needs a human: put the Mac to sleep from the Apple menu for over a minute. Nothing here sleeps it. */
    @Test
    fun wakeIsSeenAfterARealSleep() {
        optIn()
        runBlocking {
            val detector = WakeDetector()
            val start = TimeSource.Monotonic.markNow()
            val woke = mutableListOf<Duration>()
            while (start.elapsedNow() < 4.minutes && woke.isEmpty()) {
                detector.observe(Clock.System.now(), start.elapsedNow())?.let {
                    println("MANUAL wake seen after $it")
                    woke += it
                }
                delay(5.seconds)
            }
            woke.size shouldBe 1
            (woke.single() >= 60.seconds) shouldBe true
        }
    }
}
