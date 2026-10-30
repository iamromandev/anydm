package dev.anydm.desktop

import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyShortcut
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.MenuBar
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.anydm.engine.platform.desktopAppDirs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.nio.file.Path

fun main() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val source = runBlocking { openDesktopSource() }
    val firstRun = FileFirstRun(Path.of(desktopAppDirs().data.toString()))
    val model = AppModel(source, firstRun, scope)
    model.start()
    application {
        val closing = rememberCoroutineScope()
        // Every quit waits for the one stop, and the app ends even if the stop throws.
        val quit: () -> Unit = {
            closing.launch {
                try {
                    model.stop()
                } finally {
                    exitApplication()
                }
            }
        }
        Window(
            onCloseRequest = quit,
            title = "anydm",
            icon = painterResource("icon.png"),
            state = rememberWindowState(width = 1100.dp, height = 720.dp),
        ) {
            MenuBar {
                Menu("File") {
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
