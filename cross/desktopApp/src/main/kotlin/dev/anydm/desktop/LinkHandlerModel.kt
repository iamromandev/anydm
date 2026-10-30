package dev.anydm.desktop

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class LinkHandlerState(
    val loaded: Boolean = false,
    val status: LinkHandlerStatus = LinkHandlerStatus(LinkSupport.UNAVAILABLE, ""),
    val busy: Boolean = false,
    val message: String? = null,
)

/** The "open links with anydm" row of the system section (#611): the OS's answer, and the register and remove actions. */
class LinkHandlerModel(
    private val handlers: LinkHandlers,
    private val scope: CoroutineScope,
    // Registering runs programs and writes files, so never on the UI thread.
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val ui = MutableStateFlow(LinkHandlerState())
    val state: StateFlow<LinkHandlerState> = ui.asStateFlow()

    fun load() {
        scope.launch {
            val status = withContext(io) { handlers.status() }
            // A change in flight brings its own, newer answer.
            ui.update { if (it.busy) it.copy(loaded = true) else it.copy(loaded = true, status = status) }
        }
    }

    fun register() = change(LinkSupport.NOT_REGISTERED) { handlers.register() }

    fun unregister() = change(LinkSupport.REGISTERED) { handlers.unregister() }

    // Only from the state the action is for; a click from a stale screen does nothing.
    private fun change(
        from: LinkSupport,
        action: suspend () -> String?,
    ) {
        if (ui.value.busy || ui.value.status.support != from) return
        ui.update { it.copy(busy = true, message = null) }
        scope.launch {
            val problem =
                runCatching {
                    withContext(io) { action() }
                }.getOrElse { if (it is CancellationException) throw it else "Failed: ${it.message}" }
            val status = runCatching { withContext(io) { handlers.status() } }.getOrNull()
            ui.update { it.copy(busy = false, status = status ?: it.status, message = problem) }
        }
    }
}
