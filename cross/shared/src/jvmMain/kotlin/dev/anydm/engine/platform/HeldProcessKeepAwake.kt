package dev.anydm.engine.platform

import dev.anydm.engine.log.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.cancellation.CancellationException

/**
 * Keeps the machine awake with a helper program that holds the hold for as long as it runs (`caffeinate` on macOS,
 * `systemd-inhibit` on Linux). [release] kills it. A helper that ends on its own is noted and started again by the next
 * [acquire]. A missing command or a failing start is logged once per instance and never thrown.
 */
internal class HeldProcessKeepAwake(
    private val runner: ProcessRunner,
    val spec: ProcessSpec,
    private val label: String,
    private val log: Log,
    private val watchScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : KeepAwake {
    private val lock = Mutex()
    private var process: RunningProcess? = null
    private var watcher: Job? = null
    private var warned = false

    override suspend fun acquire() {
        lock.withLock {
            if (process != null) return
            try {
                when (val started = runner.start(spec)) {
                    is ProcessOutcome.Failed -> {
                        warnOnce("${started.failure}")
                    }

                    is ProcessOutcome.Ok -> {
                        val running = started.value
                        process = running
                        watcher = watchScope.launch { watch(running) }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                warnOnce(e.message ?: e::class.simpleName.orEmpty())
            }
        }
    }

    override suspend fun release() {
        lock.withLock {
            watcher?.cancel()
            watcher = null
            val running = process ?: return
            process = null
            try {
                running.stop()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                warnOnce("could not stop it: ${e.message}")
            }
        }
    }

    private suspend fun watch(running: RunningProcess) {
        val code = running.await()
        lock.withLock {
            if (process === running) {
                process = null
                watcher = null
                warnOnce("it exited with code $code")
            }
        }
    }

    private fun warnOnce(detail: String) {
        if (warned) return
        warned = true
        log.warn("platform") { "keep awake unavailable: $label: $detail" }
    }
}
