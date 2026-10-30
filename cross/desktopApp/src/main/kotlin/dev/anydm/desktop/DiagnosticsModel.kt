package dev.anydm.desktop

import dev.anydm.engine.queue.CommandResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DiagnosticsState(
    val loaded: Boolean = false,
    val debugLogging: Boolean = false,
    val busy: Boolean = false,
    val message: String? = null,
)

/** The diagnostics section (#609): debug logging, which applies at once, and the bundle to send with a bug report. */
class DiagnosticsModel(
    private val source: EngineSource,
    private val scope: CoroutineScope,
) {
    private val ui = MutableStateFlow(DiagnosticsState())
    val state: StateFlow<DiagnosticsState> = ui.asStateFlow()

    fun load() =
        scope.launch {
            guarded {
                val runtime = source.runtimeSettings()
                ui.update { it.copy(loaded = true, debugLogging = runtime.debugLogging) }
            }
        }

    fun setDebugLogging(on: Boolean) {
        scope.launch {
            guarded {
                when (val result = source.setDebugLogging(on)) {
                    is CommandResult.Done -> ui.update { it.copy(debugLogging = on, message = null) }
                    is CommandResult.Invalid -> ui.update { it.copy(message = result.reason) }
                    else -> ui.update { it.copy(message = result.toString()) }
                }
            }
        }
    }

    /** Writes the bundle to [target]. */
    fun export(target: String) {
        ui.update { it.copy(busy = true, message = null) }
        scope.launch {
            try {
                guarded {
                    val error = source.exportDiagnostics(target)
                    ui.update {
                        it.copy(
                            message =
                                if (error ==
                                    null
                                ) {
                                    "Saved the diagnostics bundle to $target."
                                } else {
                                    "The bundle couldn't be written: $error"
                                },
                        )
                    }
                }
            } finally {
                ui.update { it.copy(busy = false) }
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
