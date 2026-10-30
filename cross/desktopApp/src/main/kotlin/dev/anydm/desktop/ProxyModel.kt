package dev.anydm.desktop

import dev.anydm.engine.net.ProxyChoice
import dev.anydm.engine.net.ProxyCommandResult
import dev.anydm.engine.net.ProxyEntry
import dev.anydm.engine.queue.CommandResult
import dev.anydm.engine.store.ProxyRemoval
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ProxyState(
    val loaded: Boolean = false,
    val proxies: List<ProxyEntry> = emptyList(),
    val choice: ProxyChoice = ProxyChoice.System,
    /** The proxy being added or changed; null while only the list shows. */
    val form: ProxyForm? = null,
    val errors: Map<ProxyField, String> = emptyMap(),
    /** The proxy the user is about to remove. */
    val removing: ProxyEntry? = null,
    val message: String? = null,
    val busy: Boolean = false,
) {
    val canSave: Boolean get() = form != null && errors.isEmpty() && !busy
}

/**
 * The proxies section (#609): the saved proxies, the global choice, and the add and change form. Each action goes to
 * the engine at once, which applies it to running connections, so there is no draft to save apart from the form.
 */
class ProxyModel(
    private val source: EngineSource,
    private val scope: CoroutineScope,
) {
    private val ui = MutableStateFlow(ProxyState())
    val state: StateFlow<ProxyState> = ui.asStateFlow()

    fun load() =
        scope.launch {
            guarded {
                val proxies = source.proxies()
                val choice = source.proxyChoice()
                ui.update { it.copy(loaded = true, proxies = proxies, choice = choice) }
            }
        }

    fun add() = open(ProxyForm())

    fun edit(proxy: ProxyEntry) = open(proxyForm(proxy))

    private fun open(form: ProxyForm) = ui.update { it.copy(form = form, errors = form.validate(), message = null) }

    fun edit(change: (ProxyForm) -> ProxyForm) =
        ui.update {
            val form = change(it.form ?: return@update it)
            it.copy(form = form, errors = form.validate(), message = null)
        }

    fun cancel() = ui.update { it.copy(form = null, errors = emptyMap(), message = null) }

    fun save() {
        var form: ProxyForm? = null
        ui.update {
            // The lambda may run again; only its last run counts.
            form = null
            if (!it.canSave) return@update it
            form = it.form
            it.copy(busy = true)
        }
        val saving = form ?: return
        scope.launch {
            try {
                guarded {
                    val id = saving.id
                    val result =
                        if (id == null) {
                            source.addProxy(saving.toNew(), saving.password.ifEmpty { null })
                        } else {
                            source.updateProxy(saving.toEntry(id), saving.passwordChange)
                        }
                    saved(result)
                }
            } finally {
                ui.update { it.copy(busy = false) }
            }
        }
    }

    private suspend fun saved(result: ProxyCommandResult) {
        when (result) {
            is ProxyCommandResult.Saved -> {
                done("Saved.")
            }

            is ProxyCommandResult.PasswordLeft -> {
                done("Saved, but the old password could not be removed from the keychain.")
            }

            ProxyCommandResult.NameTaken -> {
                field(ProxyField.NAME, "Another proxy has this name.")
            }

            ProxyCommandResult.NotFound -> {
                ui.update { it.copy(form = null, errors = emptyMap(), message = "That proxy was removed in the meantime.") }
                reload()
            }

            is ProxyCommandResult.Invalid -> {
                ui.update { it.copy(message = result.reason) }
            }

            is ProxyCommandResult.KeychainFailed -> {
                field(ProxyField.PASSWORD, "The keychain can't be reached: ${result.reason}")
            }
        }
    }

    private suspend fun done(message: String) {
        ui.update { it.copy(form = null, errors = emptyMap(), message = message) }
        reload()
    }

    private fun field(
        field: ProxyField,
        text: String,
    ) = ui.update { it.copy(errors = it.errors + (field to text)) }

    private suspend fun reload() {
        val proxies = source.proxies()
        ui.update { it.copy(proxies = proxies) }
    }

    fun askRemove(proxy: ProxyEntry) = ui.update { it.copy(removing = proxy, message = null) }

    fun cancelRemove() = ui.update { it.copy(removing = null) }

    fun confirmRemove() {
        val proxy = ui.value.removing ?: return
        ui.update { it.copy(removing = null) }
        scope.launch {
            guarded {
                val message =
                    when (val removal = source.removeProxy(proxy.id)) {
                        ProxyRemoval.Removed -> "Removed ${proxy.name}."
                        ProxyRemoval.NotFound -> "That proxy was already removed."
                        ProxyRemoval.PasswordLeft -> "Removed ${proxy.name}, but its password could not be removed from the keychain."
                        is ProxyRemoval.InUse -> "${proxy.name} is still used by ${uses(removal)}, so it was kept."
                    }
                ui.update { it.copy(message = message) }
                reload()
            }
        }
    }

    private fun uses(use: ProxyRemoval.InUse): String =
        listOfNotNull(
            "${use.downloads} downloads".takeIf { use.downloads > 0 }?.let { if (use.downloads == 1) "1 download" else it },
            "${use.profiles} profiles".takeIf { use.profiles > 0 }?.let { if (use.profiles == 1) "1 profile" else it },
            "the global setting".takeIf { use.global },
        ).joinToString(", ")

    /** Sets the global choice; the engine starts running connections again through it. */
    fun choose(choice: ProxyChoice) {
        scope.launch {
            guarded {
                when (val result = source.setProxyChoice(choice)) {
                    is CommandResult.Done -> ui.update { it.copy(choice = choice, message = null) }
                    is CommandResult.Invalid -> ui.update { it.copy(message = result.reason) }
                    else -> ui.update { it.copy(message = result.toString()) }
                }
            }
        }
    }

    // A stopped engine takes no command: the screen says so instead.
    private suspend fun guarded(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: IllegalStateException) {
            ui.update { it.copy(message = "The download engine has stopped, so nothing was changed.") }
        } catch (e: IllegalArgumentException) {
            ui.update { it.copy(message = e.message ?: "The engine did not accept that proxy.") }
        }
    }
}
