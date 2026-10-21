package dev.anydm.engine.net

import dev.anydm.engine.platform.KeepAwake
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Holds and lets go of the keep-awake (NET-11). The engine tells it whether some job is transferring ([update]) and
 * whether the setting is on ([setEnabled]); it wants the hold while both are true. A wanted hold is asked for on every
 * evaluation, so a helper that died is started again. Letting go waits [grace] of continuous "not wanted", so moving
 * from one job to the next does not restart the helper; turning the setting off lets go at once. Runs on the engine's
 * publisher; a lock keeps the grace timer from interleaving with an evaluation.
 */
internal class AwakeKeeper(
    private val keepAwake: KeepAwake,
    private val scope: CoroutineScope,
    private val grace: Duration = AWAKE_GRACE,
) {
    private val lock = Mutex()
    private var enabled = true
    private var awake = false
    private var closed = false
    private var held = false
    private var timer: Job? = null

    /** The setting: off lets go at once if held; on evaluates again with the last [update]. */
    suspend fun setEnabled(on: Boolean) =
        lock.withLock {
            enabled = on
            evaluate()
        }

    /** Whether some job is transferring now. */
    suspend fun update(awake: Boolean) =
        lock.withLock {
            this.awake = awake
            evaluate()
        }

    /** The engine stops: cancels the timer and lets go if held. Later calls do nothing. */
    suspend fun close() =
        lock.withLock {
            closed = true
            stopTimer()
            if (held) {
                held = false
                keepAwake.release()
            }
        }

    private val wanted: Boolean get() = enabled && awake && !closed

    private suspend fun evaluate() {
        if (wanted) {
            stopTimer()
            keepAwake.acquire()
            held = true
            return
        }
        if (!held) return
        if (!enabled) {
            stopTimer()
            held = false
            keepAwake.release()
        } else if (timer == null) {
            timer =
                scope.launch {
                    delay(grace)
                    lock.withLock {
                        timer = null
                        if (held && !wanted) {
                            held = false
                            keepAwake.release()
                        }
                    }
                }
        }
    }

    private fun stopTimer() {
        timer?.cancel()
        timer = null
    }

    companion object {
        val AWAKE_GRACE = 5.seconds
    }
}
