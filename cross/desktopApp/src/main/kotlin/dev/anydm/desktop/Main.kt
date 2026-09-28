package dev.anydm.desktop

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.anydm.desktop.ui.ConnectScreen
import dev.anydm.desktop.ui.MainScreen
import dev.anydm.settings.JvmSettingsFile
import dev.anydm.settings.SettingsStore
import dev.anydm.settings.defaultSettingsPath
import kotlinx.coroutines.launch

/** The desktop client: Connect, then the list. */
fun main() =
    application {
        // Compose's main dispatcher is Swing's thread: the one thread TaskStore needs.
        val scope = rememberCoroutineScope()
        val model = remember { AppModel(SettingsStore(JvmSettingsFile(defaultSettingsPath())), scope) }
        Window(
            onCloseRequest = ::exitApplication,
            title = "anydm",
            state = rememberWindowState(size = DpSize(1100.dp, 760.dp)),
        ) {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface {
                    when (val screen = model.screen) {
                        is Screen.Connect -> {
                            val saved = model.settings.settings.value
                            ConnectScreen(saved.serverUrl, saved.apiKey, screen.message, model.connecting) { url, key ->
                                scope.launch { model.connect(url, key) }
                            }
                        }

                        is Screen.Main -> {
                            MainScreen(screen.store, model.settings, onSignOut = { model.signOut(null) })
                        }
                    }
                }
            }
        }
    }
