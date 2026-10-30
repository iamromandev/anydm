package dev.anydm.engine.os

import dev.anydm.engine.log.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext

/**
 * Runs the [hotkey] while its setting is on (OSI-06) and hands each press to [onPress], one after the other in the order they
 * came. The capture only drops a press in a queue, so a slow [onPress] (an add that takes a while) never holds it up. A
 * hotkey that can't start, or throws on starting, is a [status] and nothing more: the app goes on.
 */
class HotkeyService(
    private val hotkey: GlobalHotkey,
    private val log: Log = Log.None,
    private val onPress: suspend () -> Unit,
) {
    private val current = MutableStateFlow<HotkeyStatus>(HotkeyStatus.Off)
    val status: StateFlow<HotkeyStatus> = current.asStateFlow()

    /** Follows [enabled] until cancelled: starts the capture when it turns true, stops it when it turns false. */
    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun run(enabled: Flow<Boolean>) {
        enabled.distinctUntilChanged().collectLatest { on ->
            if (!on) {
                current.value = HotkeyStatus.Off
                return@collectLatest
            }
            val presses = Channel<Unit>(Channel.UNLIMITED)
            val started =
                try {
                    hotkey.start { presses.trySend(Unit) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    // A native library that is missing is an Error, not an Exception: neither may end the app.
                    HotkeyStatus.Failed(e.message ?: e::class.simpleName ?: "failed")
                }
            current.value = started
            if (started != HotkeyStatus.Active) return@collectLatest
            try {
                for (press in presses) handle()
            } finally {
                presses.close()
                withContext(NonCancellable) {
                    try {
                        hotkey.stop()
                    } catch (e: Throwable) {
                        // Letting go that fails must not take the service down with it.
                        log.warn("hotkey") { "the hotkey could not be stopped cleanly: ${e::class.simpleName}" }
                    }
                }
                current.value = HotkeyStatus.Off
            }
        }
    }

    private suspend fun handle() {
        try {
            onPress()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // The class only: the message could repeat a link or a login.
            log.warn("hotkey") { "a press could not be handled: ${e::class.simpleName}" }
        }
    }
}
