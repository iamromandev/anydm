package dev.anydm.engine.net

import dev.anydm.engine.EngineEvent
import dev.anydm.engine.log.Log
import dev.anydm.engine.platform.PowerActions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * The one-shot after-queue action (NET-10): once the queue is finished, a 30 s countdown, then sleep or shut down. The
 * engine tells it whether the queue is busy ([update], [set]) and runs it on the publisher, which serialises it.
 *
 * The action fires only after the queue was seen busy and then idle ([start] never fires, so a restart on an empty queue
 * does nothing). Busy again during the countdown cancels it and the action stays armed. When the countdown ends the
 * truth is read again ([busyNow]); if it is still idle the action is committed: the setting is reset, the meter is
 * flushed, [EngineEvent.AfterQueueDone] is told and the power call is made. From then on only the engine scope can stop
 * it, so the setting is never reset without the action running.
 */
internal class AfterQueueKeeper(
    private val scope: CoroutineScope,
    private val clock: Clock,
    private val log: Log,
    private val save: suspend (AfterQueueAction) -> Unit,
    private val busyNow: suspend () -> Boolean,
    private val flush: suspend () -> Unit,
    private val power: PowerActions,
    private val emit: (EngineEvent) -> Unit,
    private val countdown: Duration = AFTER_QUEUE_COUNTDOWN,
) {
    private val published = MutableStateFlow(AfterQueueState.Idle)

    /** The armed action and, while the countdown runs, when it ends; [AfterQueueState.Idle] until [start]. */
    val state: StateFlow<AfterQueueState> = published

    var started = false
        private set

    private var action = AfterQueueAction.NONE
    private var sawBusy = false
    private var running: Job? = null
    private var committed = false

    /** Arms the action stored from the last run. It waits for a busy-then-idle cycle: it never fires on what is there now. */
    fun start(stored: AfterQueueAction) {
        started = true
        action = stored
        sawBusy = false
        publish()
    }

    /** The queue is busy or not (see [QueueActivity.busy]). */
    fun update(busy: Boolean) {
        if (committed) return
        if (busy) {
            sawBusy = true
            if (running != null) cancelCountdown(disarmed = false)
        } else if (action != AfterQueueAction.NONE && sawBusy && running == null) {
            sawBusy = false
            startCountdown()
        }
    }

    /**
     * The user arms [new] or takes the action away (NONE), with [busy] the queue's state now. The setting is stored
     * first. Arming the same action again changes nothing; switching action during the countdown restarts it for the
     * new one; arming while busy fires when the work ends, arming an idle queue waits for the next cycle.
     */
    suspend fun set(
        new: AfterQueueAction,
        busy: Boolean,
    ) {
        save(new)
        val old = action
        if (new == old) return
        when {
            running != null && new != AfterQueueAction.NONE -> {
                cancelCountdown(disarmed = false)
                action = new
                startCountdown()
            }

            running != null -> {
                cancelCountdown(disarmed = true)
                action = new
                publish()
            }

            else -> {
                action = new
                sawBusy = new != AfterQueueAction.NONE && busy
                publish()
            }
        }
    }

    private fun publish(endsAt: Instant? = null) {
        published.value = AfterQueueState(action, endsAt)
    }

    // The action stays as it is for a plain cancel; the caller sets it for a disarm or a switch.
    private fun cancelCountdown(disarmed: Boolean) {
        running?.cancel()
        running = null
        emit(EngineEvent.AfterQueueCancelled(action, disarmed))
        publish()
    }

    private fun startCountdown() {
        val endsAt = clock.now() + countdown
        publish(endsAt)
        emit(EngineEvent.AfterQueueCountdown(action, endsAt))
        running =
            scope.launch {
                delay(countdown)
                finish()
            }
    }

    private suspend fun finish() {
        val armed = action
        if (busyNow()) {
            running = null
            sawBusy = true
            emit(EngineEvent.AfterQueueCancelled(armed, false))
            publish()
            return
        }
        committed = true
        running = null
        try {
            save(AfterQueueAction.NONE)
            action = AfterQueueAction.NONE
            publish()
            flush()
            emit(EngineEvent.AfterQueueDone(armed))
            val failure = run(armed)
            if (failure != null) emit(EngineEvent.AfterQueueFailed(armed, failure))
        } finally {
            committed = false
        }
    }

    // Null when the machine took the call; else why not. A throw from the platform counts as a failed call.
    private suspend fun run(armed: AfterQueueAction): String? {
        val what = if (armed == AfterQueueAction.SLEEP) "sleep" else "shut-down"
        val missing = "the $what command is missing or failed"
        return try {
            val ok = if (armed == AfterQueueAction.SLEEP) power.sleep() else power.shutDown()
            if (ok) null else missing
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            log.warn("net") { "after queue: $armed: ${e.message}" }
            "$missing: ${e.message}"
        }
    }

    companion object {
        val AFTER_QUEUE_COUNTDOWN = 30.seconds
    }
}
