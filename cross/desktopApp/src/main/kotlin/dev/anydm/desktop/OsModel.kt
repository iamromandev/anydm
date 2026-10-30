package dev.anydm.desktop

import dev.anydm.engine.os.HotkeyStatus
import dev.anydm.engine.queue.CommandResult
import dev.anydm.engine.store.Settings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class OsState(
    val loaded: Boolean = false,
    val notifications: Boolean = true,
    val clipboardWatch: Boolean = false,
    val globalHotkey: Boolean = false,
    val hotkeyStatus: HotkeyStatus = HotkeyStatus.Off,
    /** The extension origins the browser bridge answers, sorted. */
    val origins: List<String> = emptyList(),
    val originError: String? = null,
    val message: String? = null,
)

/** What the hotkey section says about [status]: whether it works and, when it doesn't, what to do. */
fun hotkeyHelp(
    status: HotkeyStatus,
    on: Boolean,
): String {
    val chord = if (IsMac) "Cmd+Shift+D" else "Ctrl+Shift+D"
    return when {
        !on -> {
            "Off. When it is on, $chord queues the link you copied, from any app."
        }

        status is HotkeyStatus.Active -> {
            "On: $chord queues the link you copied, from any app."
        }

        status is HotkeyStatus.NeedsPermission -> {
            "macOS needs your permission first: allow anydm under Privacy & Security, Accessibility. It works as soon as you do."
        }

        status is HotkeyStatus.Unsupported -> {
            "This system can't do it: ${status.reason}"
        }

        status is HotkeyStatus.Failed -> {
            "It should have worked and didn't: ${status.reason}"
        }

        else -> {
            "On: it starts with the system integration."
        }
    }
}

/**
 * The notifications, clipboard watch, global hotkey and browser bridge section (#609). The switches go to the engine at
 * once and it applies them; the hotkey's status is the service's, as it changes.
 */
class OsModel(
    private val source: EngineSource,
    private val scope: CoroutineScope,
) {
    private val ui = MutableStateFlow(OsState())
    val state: StateFlow<OsState> = ui.asStateFlow()
    private var following = false

    fun load() =
        scope.launch {
            guarded {
                val runtime = source.runtimeSettings()
                val os = source.osSettings.value
                ui.update {
                    it.copy(
                        loaded = true,
                        notifications = runtime.notifications,
                        clipboardWatch = os.clipboardWatch,
                        globalHotkey = os.globalHotkey,
                        hotkeyStatus = source.hotkeyStatus.value,
                        origins = runtime.bridgeOrigins.sorted(),
                    )
                }
            }
            follow()
        }

    // The service's status changes on its own; this follows it for as long as the app runs.
    private fun follow() {
        if (following) return
        following = true
        scope.launch { source.hotkeyStatus.collect { status -> ui.update { it.copy(hotkeyStatus = status) } } }
    }

    fun setNotifications(on: Boolean) = apply({ source.setNotifications(on) }) { it.copy(notifications = on) }

    fun setClipboardWatch(on: Boolean) = apply({ source.setClipboardWatch(on) }) { it.copy(clipboardWatch = on) }

    fun setGlobalHotkey(on: Boolean) = apply({ source.setGlobalHotkey(on) }) { it.copy(globalHotkey = on) }

    /** Allows [text]; false, with the reason in the state, when it isn't an origin or is allowed already, so the caller keeps the text. */
    fun addOrigin(text: String): Boolean {
        val origin = text.trim()
        val error =
            when {
                !origin.matches(Settings.EXTENSION_ORIGIN) -> "Use chrome-extension://<id> or moz-extension://<id>."
                origin in ui.value.origins -> "That origin is already allowed."
                else -> null
            }
        ui.update { it.copy(originError = error) }
        if (error == null) changeOrigins { (it + origin).distinct() }
        return error == null
    }

    fun removeOrigin(origin: String) =
        changeOrigins { current ->
            val left = current - origin
            if (left.isEmpty()) {
                ui.update { it.copy(message = "Keep at least one origin: with none, the bridge would answer any extension.") }
                null
            } else {
                left
            }
        }

    fun resetOrigins() = changeOrigins { listOf(Settings.CHROME_EXTENSION_ORIGIN) }

    // One change at a time, each built from the origins the one before left, so quick clicks all land.
    private val originLock = Mutex()

    private fun changeOrigins(next: (List<String>) -> List<String>?) {
        scope.launch {
            originLock.withLock {
                guarded {
                    val origins = next(ui.value.origins) ?: return@guarded
                    when (val result = source.setBridgeOrigins(origins.toSet())) {
                        is CommandResult.Done -> ui.update { it.copy(origins = origins.sorted(), originError = null, message = null) }
                        is CommandResult.Invalid -> ui.update { it.copy(message = result.reason) }
                        else -> ui.update { it.copy(message = result.toString()) }
                    }
                }
            }
        }
    }

    // The change is shown only once the engine has taken it.
    private fun apply(
        command: suspend () -> CommandResult,
        change: (OsState) -> OsState,
    ) {
        scope.launch {
            guarded {
                when (val result = command()) {
                    is CommandResult.Done -> ui.update { change(it).copy(message = null) }
                    is CommandResult.Invalid -> ui.update { it.copy(message = result.reason) }
                    else -> ui.update { it.copy(message = result.toString()) }
                }
            }
        }
    }

    private suspend fun guarded(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: IllegalStateException) {
            ui.update { it.copy(message = "The download engine has stopped, so nothing was changed.") }
        }
    }
}
