package dev.anydm.desktop

import dev.anydm.engine.os.PairedBrowser
import dev.anydm.engine.os.PairedBrowsers
import dev.anydm.engine.os.PairingState
import dev.anydm.engine.os.RevokeResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** The code on show: [grouped] is a secret, so it is left out of the text of the state. */
class ShownCode(
    val grouped: String,
    val expiresAt: Instant,
    val secondsLeft: Long,
    /** Too many wrong tries in the last minute: the code works again in a while. */
    val throttled: Boolean = false,
) {
    fun copy(
        secondsLeft: Long = this.secondsLeft,
        throttled: Boolean = this.throttled,
    ) = ShownCode(grouped, expiresAt, secondsLeft, throttled)

    override fun toString(): String = "ShownCode(code=***, secondsLeft=$secondsLeft, throttled=$throttled)"
}

/** Why the last code is no longer on show. */
enum class CodeEnd { BURNED, EXPIRED }

/** The paired browsers, as far as they are known. */
sealed interface BrowserList {
    data object Loading : BrowserList

    data class Listed(
        val browsers: List<PairedBrowser>,
    ) : BrowserList

    /** The pairings can't be read now: not the same as nothing paired. */
    data object Unavailable : BrowserList
}

data class PairBrowserState(
    val code: ShownCode? = null,
    val generating: Boolean = false,
    val ended: CodeEnd? = null,
    /** What happened that is worth a line: "Paired Firefox". */
    val notice: String? = null,
    val error: String? = null,
    val browsers: BrowserList = BrowserList.Loading,
    /** The browser a revoke is being confirmed for. */
    val confirm: PairedBrowser? = null,
    val revoking: Boolean = false,
    /** The dialog has gone: nothing more is made or shown. */
    val closed: Boolean = false,
)

/**
 * The Pair a browser dialog: one pairing code at a time with its countdown and what became of it (asked about once every
 * [poll]), and the list of paired browsers with Revoke. The pairings are files: every engine call runs on [io]. The code is
 * a secret kept only here, and [close] clears it and tells the engine to drop it, so it does not stay good for its two minutes.
 *
 * A closed model makes and shows nothing more. [close] and a revoke the user confirmed go on to their end even when [scope] is
 * cancelled at once after (the window drops the dialog's scope with the dialog).
 */
class PairBrowserModel(
    private val source: EngineSource,
    private val scope: CoroutineScope,
    private val clock: () -> Instant = { Clock.System.now() },
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val poll: Duration = 1.seconds,
) {
    private val mutable = MutableStateFlow(PairBrowserState())
    val state: StateFlow<PairBrowserState> = mutable.asStateFlow()

    // The job that makes a code and then watches it; a newer ask, or the close, ends it. Each ask waits for the one before it
    // (it joins it), so the engine is called in order and a close that joins the last one has joined them all.
    @Volatile
    private var making: Job? = null

    // The code the engine made last, so an end names the code it means and leaves a newer one alone.
    @Volatile
    private var offered: PairingCode? = null

    // Counts the asks, so a code that arrives after a newer ask is not put on show over it.
    private val asks = AtomicInteger()

    // A code was asked for: the engine may be holding one.
    @Volatile
    private var requested = false

    /** Reads the paired browsers; a problem or note from before goes. */
    fun load(): Job =
        scope.launch {
            val list = read()
            mutable.update { it.copy(browsers = list, error = null, notice = null) }
        }

    private suspend fun read(): BrowserList =
        try {
            when (val found = withContext(io) { source.pairedBrowsers() }) {
                is PairedBrowsers.Listed -> BrowserList.Listed(found.browsers)
                PairedBrowsers.Unavailable -> BrowserList.Unavailable
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            BrowserList.Unavailable
        }

    /**
     * Makes a new code, which replaces any on show (the engine ends the old one). The job it returns is done when the code is
     * on show or could not be made, not when the code ends: the code is watched after that.
     */
    fun generate(): Job {
        val shown = Job()
        if (mutable.value.closed) return shown.apply { complete() }
        requested = true
        val previous = making
        previous?.cancel()
        val mine = asks.incrementAndGet()
        mutable.update { it.copy(code = null, ended = null, notice = null, error = null, generating = true) }
        val job =
            scope.launch {
                try {
                    // The older call is over (it was cancelled above) before this one asks the engine. This wait is not cut short
                    // when this job is cancelled too: the close, which joins the newest job, then has joined every older one.
                    withContext(NonCancellable) { previous?.join() }
                    // A close or a newer ask may have landed while that ended: then this call is not made at all.
                    if (mutable.value.closed || asks.get() != mine) return@launch
                    ensureActive()
                    val made = withContext(io) { source.startPairing() }
                    offered = made
                    // A close or a newer ask may have landed while the call came back.
                    ensureActive()
                    val left = secondsLeft(made.expiresAt)
                    var onShow = false
                    mutable.update { s ->
                        onShow = !s.closed && asks.get() == mine
                        if (onShow) s.copy(code = ShownCode(made.grouped, made.expiresAt, left), generating = false) else s
                    }
                    if (!onShow) return@launch
                    shown.complete()
                    watch(made, mine)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // A newer ask or a close has taken over: what happened to this one is no longer news.
                    if (asks.get() == mine && !mutable.value.closed) {
                        mutable.update { s ->
                            if (s.closed) s else s.copy(generating = false, error = "Couldn't make a code: ${reason(e)}")
                        }
                        // The code that was on show is gone from the screen but is still good in the engine.
                        endInEngine()
                    }
                } finally {
                    shown.complete()
                }
            }
        making = job
        job.invokeOnCompletion { mutable.update { s -> if (making === job) s.copy(generating = false) else s } }
        return shown
    }

    /** Follows [made] until it ends. Runs in the job that made it, so ending that job ends this. */
    private suspend fun watch(
        made: PairingCode,
        mine: Int,
    ) {
        while (true) {
            delay(poll)
            val now =
                try {
                    withContext(io) { made.state() }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (asks.get() == mine && !mutable.value.closed) {
                        mutable.update { s -> if (s.closed) s else s.copy(code = null, error = "Lost touch with the app: ${reason(e)}") }
                        endInEngine()
                    }
                    return
                }
            val left = secondsLeft(made.expiresAt)
            when (now) {
                PairingState.PAIRED -> {
                    paired()
                    return
                }

                PairingState.BURNED -> {
                    mutable.update { s -> if (s.closed) s else s.copy(code = null, ended = CodeEnd.BURNED) }
                    return
                }

                PairingState.EXPIRED -> {
                    mutable.update { s -> if (s.closed) s else s.copy(code = null, ended = CodeEnd.EXPIRED) }
                    return
                }

                PairingState.WAITING, PairingState.THROTTLED -> {
                    if (left <= 0) {
                        mutable.update { s -> if (s.closed) s else s.copy(code = null, ended = CodeEnd.EXPIRED) }
                        return
                    }
                    mutable.update { s ->
                        if (s.closed) s else s.copy(code = s.code?.copy(secondsLeft = left, throttled = now == PairingState.THROTTLED))
                    }
                }
            }
        }
    }

    private suspend fun paired() {
        // Which browser is the new one can only be told against a list that was read before: otherwise none is named.
        val before = (mutable.value.browsers as? BrowserList.Listed)?.browsers?.map { it.id }?.toSet()
        mutable.update { s -> if (s.closed) s else s.copy(code = null, ended = null, error = null) }
        // A newer ask cancels the job this runs in; the pairing happened all the same, so the list is read to its end.
        val list = withContext(NonCancellable) { read() }
        val fresh = if (before == null) emptyList() else (list as? BrowserList.Listed)?.browsers.orEmpty().filter { it.id !in before }
        val note = if (fresh.isEmpty()) "Paired a browser" else "Paired " + fresh.joinToString { it.label }
        mutable.update { s -> if (s.closed) s else s.copy(browsers = list, notice = note) }
    }

    /**
     * Tells the engine to drop the code, whatever has become of the job that asked: it runs to its end even if that job is being
     * cancelled. A stopped engine has no code left to end, so a failure is not a problem.
     */
    private suspend fun endInEngine() =
        withContext(NonCancellable + io) {
            try {
                source.cancelPairing(offered)
            } catch (e: Exception) {
                // Nothing to end: the engine has stopped.
            }
        }

    private fun secondsLeft(expiresAt: Instant): Long = (expiresAt - clock()).inWholeMilliseconds.let { Math.ceilDiv(it, 1000L) }

    fun askRevoke(id: String) {
        val row = (mutable.value.browsers as? BrowserList.Listed)?.browsers?.firstOrNull { it.id == id } ?: return
        mutable.update { it.copy(confirm = row, error = null, notice = null) }
    }

    fun dismissRevoke() = mutable.update { it.copy(confirm = null) }

    /**
     * Revokes the browser that was asked about, and only that one. Once confirmed it is not given up when the dialog goes: the
     * user said Revoke, and a browser left paired is the worse mistake.
     */
    fun confirmRevoke(): Job {
        val row = mutable.value.confirm ?: return scope.launch { }
        mutable.update { it.copy(confirm = null, revoking = true, error = null, notice = null) }
        // ATOMIC: not cancelled before it starts, which the scope's cancelling right after the click would do.
        return scope.launch(start = CoroutineStart.ATOMIC) {
            try {
                when (withContext(NonCancellable + io) { source.revokeBrowser(row.id) }) {
                    RevokeResult.REVOKED -> {
                        mutable.update { s ->
                            val list = s.browsers as? BrowserList.Listed
                            s.copy(browsers = list?.copy(browsers = list.browsers.filter { it.id != row.id }) ?: s.browsers)
                        }
                    }

                    RevokeResult.NOT_FOUND -> {
                        val list = read()
                        mutable.update { it.copy(browsers = list) }
                    }

                    RevokeResult.NOT_SAVED -> {
                        val list = read()
                        mutable.update { it.copy(browsers = list, error = NOT_SAVED) }
                    }

                    RevokeResult.STORE_UNAVAILABLE -> {
                        mutable.update { it.copy(error = STORE_UNAVAILABLE) }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutable.update { it.copy(error = "Couldn't revoke: ${reason(e)}") }
            } finally {
                mutable.update { it.copy(revoking = false) }
            }
        }
    }

    /**
     * The dialog is closing: no code is kept, none is made, nobody is asked about one, and the engine is told to end the one it
     * holds. The job is done when the engine has been told (at once if there is nothing to tell).
     */
    fun close(): Job {
        val before =
            mutable.getAndUpdate {
                it.copy(
                    code = null,
                    ended = null,
                    generating = false,
                    confirm = null,
                    notice = null,
                    closed = true,
                )
            }
        val ending = making
        ending?.cancel()
        if (before.closed || !requested) return Job().apply { complete() }
        // ATOMIC and not cancellable: the window drops the scope right after this, and the code must not outlive it. The call that
        // is making a code may be midway: it is waited for, or the code it brings would be made after the engine was told.
        return scope.launch(start = CoroutineStart.ATOMIC) {
            withContext(NonCancellable) {
                ending?.join()
                endInEngine()
            }
        }
    }

    private fun reason(e: Exception) = e.message ?: e::class.simpleName ?: "unknown error"

    private companion object {
        const val NOT_SAVED =
            "Revoked, but the change could not be saved: the revoked browser may be accepted again after a restart of anydm."
        const val STORE_UNAVAILABLE = "The paired browsers can't be read right now, so nothing was revoked. Try again later."
    }
}
