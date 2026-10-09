package dev.anydm.desktop

import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyShortcut
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.MenuBar
import androidx.compose.ui.window.Notification
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberTrayState
import androidx.compose.ui.window.rememberWindowState
import dev.anydm.desktop.chrome.LocalWindowDrag
import dev.anydm.desktop.list.Command
import dev.anydm.desktop.theme.AppIcon
import dev.anydm.desktop.theme.DesktopTheme
import dev.anydm.desktop.theme.isMac
import dev.anydm.desktop.theme.shortcutLabel
import dev.anydm.desktop.ui.ConnectScreen
import dev.anydm.desktop.ui.MainScreen
import dev.anydm.desktop.ui.TrayIcon
import dev.anydm.desktop.ui.TrayTemplateIcon
import dev.anydm.settings.JvmSettingsFile
import dev.anydm.settings.SettingsStore
import dev.anydm.settings.defaultSettingsPath
import dev.anydm.store.BulkAction
import dev.anydm.store.StoreEvent
import dev.anydm.store.Tone
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch

/** The desktop client: Connect, then the list, with a tray that keeps it running. */
fun main() {
    System.setProperty("apple.awt.use-file-dialog-packages", "false")
    if (isMac()) System.setProperty("apple.awt.enableTemplateImages", "true")
    application {
        // Compose's main dispatcher is Swing's thread: the one thread TaskStore needs.
        val scope = rememberCoroutineScope()
        val model = remember { AppModel(SettingsStore(JvmSettingsFile(defaultSettingsPath())), scope) }
        var visible by remember { mutableStateOf(true) }
        val trayState = rememberTrayState()
        val commands = remember { MutableSharedFlow<Command>(extraBufferCapacity = 8) }
        val store = (model.screen as? Screen.Main)?.store

        Tray(
            icon = if (isMac()) TrayTemplateIcon else TrayIcon,
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
            icon = AppIcon,
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
            val mac = isMac()

            fun key(k: Key) = KeyShortcut(k, meta = mac, ctrl = !mac)
            val onList = model.screen is Screen.Main
            MenuBar {
                Menu("File") {
                    Item("Add link", enabled = onList, shortcut = key(Key.L)) { commands.tryEmit(Command.FOCUS_LINK) }
                    Item("Search", enabled = onList, shortcut = key(Key.F)) { commands.tryEmit(Command.FOCUS_SEARCH) }
                    Item("Open .torrent…", enabled = onList, shortcut = key(Key.O)) { commands.tryEmit(Command.OPEN_TORRENT) }
                    Item("Settings…", enabled = onList, shortcut = key(Key.Comma)) { commands.tryEmit(Command.SETTINGS) }
                    if (!mac) {
                        Separator()
                        Item("Quit", onClick = ::exitApplication)
                    }
                }
                Menu("Edit") {
                    Item("Select all    ${shortcutLabel("A")}", enabled = onList) { commands.tryEmit(Command.SELECT_ALL) }
                    Item("Copy link    ${shortcutLabel("C")}", enabled = onList) { commands.tryEmit(Command.COPY) }
                }
                Menu("Download") {
                    Item("Pause or resume    Space", enabled = onList) { commands.tryEmit(Command.PAUSE_RESUME) }
                    Item("Open    ⏎", enabled = onList) { commands.tryEmit(Command.OPEN) }
                    Item("Save to Downloads", enabled = onList, shortcut = key(Key.S)) { commands.tryEmit(Command.SAVE) }
                    Item("Retry failed", enabled = onList, shortcut = key(Key.R)) { commands.tryEmit(Command.RETRY) }
                    Separator()
                    Item("Remove…    ${if (mac) "⌫" else "Delete"}", enabled = onList) { commands.tryEmit(Command.REMOVE) }
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
                            MainScreen(
                                screen.store,
                                screen.search,
                                screen.batches,
                                screen.categories,
                                model.settings,
                                screen.fileUrl,
                                commands = commands,
                                onSignOut = { model.signOut(null) },
                            )
                        }
                    }
                }
            }
        }
    }
}
