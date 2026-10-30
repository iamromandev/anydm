package dev.anydm.desktop

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyShortcut
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.MenuBar
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.isTraySupported
import androidx.compose.ui.window.rememberWindowState
import dev.anydm.engine.os.FileSingleInstance
import dev.anydm.engine.os.SingleInstance
import dev.anydm.engine.platform.desktopAppDirs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val dirs = desktopAppDirs()
    // One app per user: a second launch hands its arguments to the running one and ends before any engine opens.
    val ready = CompletableDeferred<AppModel>()
    val instance = FileSingleInstance(File(dirs.data.toString()))
    val role = runBlocking { instance.start(args.toList()) { handed -> ready.await().launch(handed, second = true) } }
    if (role == SingleInstance.Role.HANDED_OVER) {
        instance.close()
        exitProcess(0)
    }
    val source = runBlocking { openDesktopSource() }
    val firstRun = FileFirstRun(Path.of(dirs.data.toString()))
    // Quit after the queue is chosen in the app; the window's quit is only there once the application runs.
    val quitApp = AtomicReference<() -> Unit> {}
    val window = WindowModel(PreferencesAppPrefs(), trayAvailable = isTraySupported)
    val model = AppModel(source, firstRun, scope, DesktopFileActions(), onQuit = { quitApp.get()() }, window = window)
    model.start()
    model.launch(args.toList(), second = false)
    ready.complete(model)
    application {
        val closing = rememberCoroutineScope()
        // Every quit waits for the one stop, and the app ends even if the stop throws.
        val quit: () -> Unit = {
            closing.launch {
                try {
                    model.stop()
                } finally {
                    instance.close()
                    exitApplication()
                }
            }
        }
        quitApp.set(quit)
        val shown by window.state.collectAsState()
        val ui by model.state.collectAsState()
        val osState by model.os.collectAsState()
        if (window.trayAvailable) {
            Tray(
                icon = painterResource("icon.png"),
                tooltip = trayTooltip(ui),
                onAction = model::trayClicked,
                menu = {
                    trayMenu(shown, ui.globallyPaused, hasLast = osState.lastNotified != null).forEach { item ->
                        Item(item.label, enabled = item.enabled, onClick = { model.trayAction(item.action) })
                    }
                },
            )
        }
        Window(
            onCloseRequest = model::closeRequested,
            visible = shown.visible,
            title = "anydm",
            icon = painterResource("icon.png"),
            state = rememberWindowState(width = 1280.dp, height = 800.dp),
        ) {
            MenuBar {
                Menu("File") {
                    Item("Add…", shortcut = KeyShortcut(Key.N, ctrl = !IsMac, meta = IsMac), onClick = { model.openAdd() })
                    Item("Quit", shortcut = KeyShortcut(Key.Q, ctrl = !IsMac, meta = IsMac), onClick = quit)
                }
                Menu("View") {
                    Item(
                        "Settings",
                        shortcut = KeyShortcut(Key.Comma, ctrl = !IsMac, meta = IsMac),
                        onClick = { model.navigate(Route.Settings) },
                    )
                    SectionKeys.forEach { (key, filter) ->
                        Item(
                            title(Route.Downloads(filter)),
                            shortcut = KeyShortcut(key, ctrl = !IsMac, meta = IsMac),
                            onClick = { model.navigate(Route.Downloads(filter)) },
                        )
                    }
                }
            }
            AnydmTheme { Shell(model) }
        }
    }
}
