package dev.anydm.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.anydm.api.AnydmApi
import dev.anydm.api.ServerConfig
import dev.anydm.api.createAnydmApi
import dev.anydm.api.normalized
import dev.anydm.desktop.ui.connectError
import dev.anydm.settings.SettingsStore
import dev.anydm.store.StoreEvent
import dev.anydm.store.TaskStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Which screen shows: Connect (with why, if a connection failed), or the list. */
sealed interface Screen {
    data class Connect(
        val message: String?,
    ) : Screen

    data class Main(
        val store: TaskStore,
    ) : Screen
}

/**
 * The app's state above any one screen: the settings, the server it's talking to, and the
 * live [TaskStore]. [scope] runs on `Dispatchers.Main` (Swing's thread), which the store
 * needs. Nothing is saved until the server has answered.
 */
class AppModel(
    val settings: SettingsStore,
    private val scope: CoroutineScope,
) {
    var screen: Screen by mutableStateOf(Screen.Connect(null))
        private set
    var connecting: Boolean by mutableStateOf(false)
        private set

    private var api: AnydmApi? = null
    private var watcher: Job? = null

    init {
        val saved = settings.settings.value
        if (saved.serverUrl.isNotBlank()) scope.launch { connect(saved.serverUrl, saved.apiKey) }
    }

    /** Ask the server for its counts; only when it answers are the URL and key saved and the list opened. */
    suspend fun connect(
        url: String,
        key: String?,
    ) {
        connecting = true
        val config = ServerConfig(url, key).normalized()
        val candidate = createAnydmApi(config)
        val failure =
            try {
                candidate.summary()
                null
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                connectError(error, config.baseUrl)
            }
        connecting = false
        if (failure != null) {
            candidate.close()
            screen = Screen.Connect(failure)
            return
        }
        settings.update { it.copy(serverUrl = config.baseUrl, apiKey = config.apiKey) }
        close()
        api = candidate
        val store = TaskStore(candidate, scope, System::currentTimeMillis)
        store.start()
        screen = Screen.Main(store)
        // A 401 later, from the stream or an action, returns here with the key's message.
        watcher =
            scope.launch {
                store.events.filterIsInstance<StoreEvent.SignedOut>().first()
                signOut("The server refused the key. Check it matches API_KEY in api/.env.")
            }
    }

    fun signOut(message: String?) {
        close()
        screen = Screen.Connect(message)
    }

    private fun close() {
        watcher?.cancel()
        (screen as? Screen.Main)?.store?.stop()
        api?.close()
        api = null
    }
}
