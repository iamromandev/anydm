package dev.anydm.engine.platform

import com.sun.jna.platform.win32.Kernel32
import dev.anydm.engine.log.Log
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import kotlin.coroutines.cancellation.CancellationException

/** The one Windows call keep-awake needs, as a seam so tests never touch the real one. */
internal interface ExecutionStateApi {
    /** SetThreadExecutionState: returns the previous state, 0 on failure. */
    fun set(flags: Int): Int
}

/** Windows' own answer, through JNA; only touched on Windows. */
internal object JnaExecutionState : ExecutionStateApi {
    override fun set(flags: Int): Int = Kernel32.INSTANCE.SetThreadExecutionState(flags)
}

/**
 * Keeps Windows from sleeping on idle with SetThreadExecutionState. `ES_CONTINUOUS` applies to the calling thread only,
 * so every call goes to one long-lived daemon thread, created on the first [acquire]. A failing call is logged once and
 * never thrown. The screen may still sleep (no `ES_DISPLAY_REQUIRED`).
 */
internal class WindowsKeepAwake(
    private val api: ExecutionStateApi,
    private val log: Log,
) : KeepAwake {
    private val dispatcher by lazy {
        Executors
            .newSingleThreadExecutor { task -> Thread(task, "anydm-keep-awake").apply { isDaemon = true } }
            .asCoroutineDispatcher()
    }

    // Read and written only on the keep-awake thread.
    private var held = false
    private var warned = false

    override suspend fun acquire() =
        withContext(dispatcher) {
            if (held) return@withContext
            if (call(ES_CONTINUOUS or ES_SYSTEM_REQUIRED)) held = true
        }

    override suspend fun release() {
        if (!held) return
        withContext(dispatcher) {
            if (!held) return@withContext
            held = false
            call(ES_CONTINUOUS)
        }
    }

    private fun call(flags: Int): Boolean =
        try {
            if (api.set(flags) != 0) {
                true
            } else {
                warnOnce("SetThreadExecutionState failed")
                false
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            warnOnce(e.message ?: e::class.simpleName.orEmpty())
            false
        } catch (e: LinkageError) {
            warnOnce(e.message ?: e::class.simpleName.orEmpty())
            false
        }

    private fun warnOnce(detail: String) {
        if (warned) return
        warned = true
        log.warn("platform") { "keep awake unavailable: $detail" }
    }

    private companion object {
        val ES_CONTINUOUS = 0x80000000.toInt()
        const val ES_SYSTEM_REQUIRED = 0x1
    }
}
