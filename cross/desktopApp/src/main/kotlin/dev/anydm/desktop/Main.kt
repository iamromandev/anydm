package dev.anydm.desktop

import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Notification
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberTrayState
import androidx.compose.ui.window.rememberWindowState
import dev.anydm.desktop.chrome.LocalWindowDrag
import dev.anydm.desktop.theme.DesktopTheme
import dev.anydm.desktop.theme.isMac
import dev.anydm.desktop.ui.ConnectScreen
import dev.anydm.desktop.ui.MainScreen
import dev.anydm.desktop.ui.TrayIcon
import dev.anydm.settings.JvmSettingsFile
import dev.anydm.settings.SettingsStore
import dev.anydm.settings.defaultSettingsPath
import dev.anydm.store.BulkAction
import dev.anydm.store.StoreEvent
import dev.anydm.store.Tone
import kotlinx.coroutines.launch

/** The desktop client: Connect, then the list, with a tray that keeps it running. */
fun main() {
    System.setProperty("apple.awt.use-file-dialog-packages", "false")
    application {
        // Compose's main dispatcher is Swing's thread: the one thread TaskStore needs.
        val scope = rememberCoroutineScope()
        val model = remember { AppModel(SettingsStore(JvmSettingsFile(defaultSettingsPath())), scope) }
        var visible by remember { mutableStateOf(true) }
        val trayState = rememberTrayState()
        val store = (model.screen as? Screen.Main)?.store

        Tray(
            icon = TrayIcon,
            state = trayState,
            tooltip = "anydm",
            onAction = { visible = true },
            menu = {
                Item("Show", onClick = { visible = true })
                Item("Pause all", enabled = store != null, onClick = { scope.launch { store?.bulk(BulkAction.PAUSE_ALL) } })
                Item("Resume all", enabled = store != null, onClick = { scope.launch { store?.bulk(BulkAction.RESUME_ALL) } })
                Separator()
                Item("Quit", onClick = ::exitApplication)
            },
        )

        Window(
            // Closing keeps anydm in the tray; Quit there ends it (spec: Tray).
            onCloseRequest = { visible = false },
            visible = visible,
            title = "anydm",
            state = rememberWindowState(size = DpSize(1100.dp, 760.dp)),
        ) {
            // Unified toolbar (spec: "The window"): content runs under a transparent title bar on macOS.
            if (isMac()) {
                LaunchedEffect(Unit) {
                    window.rootPane.putClientProperty("apple.awt.fullWindowContent", true)
                    window.rootPane.putClientProperty("apple.awt.transparentTitleBar", true)
                    window.rootPane.putClientProperty("apple.awt.windowTitleVisible", false)
                }
            }
            // A notice becomes a native notification when the window isn't in front.
            LaunchedEffect(store) {
                store?.events?.collect { event ->
                    if (event is StoreEvent.Said && !(visible && window.isFocused)) {
                        val type =
                            when (event.notice.tone) {
                                Tone.ERROR -> Notification.Type.Error
                                Tone.SUCCESS -> Notification.Type.Info
                                Tone.INFO -> Notification.Type.None
                            }
                        trayState.sendNotification(Notification("anydm", event.notice.message, type))
                    }
                }
            }
            DesktopTheme {
                CompositionLocalProvider(LocalWindowDrag provides { content -> WindowDraggableArea { content() } }) {
                    when (val screen = model.screen) {
                        is Screen.Connect -> {
                            val saved = model.settings.settings.value
                            ConnectScreen(saved.serverUrl, saved.apiKey, screen.message, model.connecting) { url, key ->
                                scope.launch { model.connect(url, key) }
                            }
                        }

                        is Screen.Main -> {
                            MainScreen(screen.store, model.settings, screen.fileUrl, onSignOut = { model.signOut(null) })
                        }
                    }
                }
            }
        }
    }
}
