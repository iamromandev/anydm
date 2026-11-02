package dev.anydm.desktop

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Runs [action] once however many callers ask: the first runs it, the rest wait for it and see its outcome. A second quit
 * must not end the app while the first stop is still saving.
 */
internal class Once(
    private val action: suspend () -> Unit,
) {
    private val started = AtomicBoolean(false)
    private val done = CompletableDeferred<Unit>()

    suspend fun run() {
        if (!started.compareAndSet(false, true)) return done.await()
        try {
            action()
            done.complete(Unit)
        } catch (e: Throwable) {
            // A cancelled first quit must not leave the others waiting for ever.
            if (e is CancellationException) done.cancel(e) else done.completeExceptionally(e)
            throw e
        }
    }
}

/** Starts the source; a start that fails is reported, not thrown, so the window still opens and shows the failed state. */
internal suspend fun EngineSource.startGuarded(report: (Throwable) -> Unit) {
    try {
        start()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        report(e)
    }
}

/** How long a quit waits for an engine that is still starting before it stops it anyway. */
internal val START_GRACE = 5.seconds

/**
 * The engine's start and its one stop. The start runs behind the window and a failure is reported, not thrown; the stop waits
 * for a start in progress, but only for [grace], and runs once however many quits ask.
 */
internal class EngineLifecycle(
    private val source: EngineSource,
    private val grace: Duration = START_GRACE,
    private val report: (Throwable) -> Unit,
) {
    private val started = CompletableDeferred<Unit>()
    private val stop =
        Once {
            withTimeoutOrNull(grace) { started.await() }
            source.stop()
        }

    suspend fun start() {
        try {
            source.startGuarded(report)
        } finally {
            started.complete(Unit)
        }
    }

    suspend fun stopOnce() = stop.run()
}
