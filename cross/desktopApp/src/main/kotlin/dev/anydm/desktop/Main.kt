package dev.anydm.desktop

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyShortcut
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.MenuBar
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.anydm.engine.os.FileSingleInstance
import dev.anydm.engine.os.SingleInstance
import dev.anydm.engine.platform.desktopAppDirs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

private const val MIN_WINDOW_WIDTH = 900
private const val MIN_WINDOW_HEIGHT = 640

fun main(args: Array<String>) {
    if (args.firstOrNull() == SelfCheck.FLAG) exitProcess(selfCheck(args.getOrNull(1)))
    // macOS hands links and files over as events, the first launch's too; they wait until a screen takes them.
    val opens = OpenEvents()
    val dirs = desktopAppDirs()
    // One app per user: a second launch hands its arguments to the running one and ends before any engine opens.
    val instance = FileSingleInstance(File(dirs.data.toString()))
    val role = runBlocking { instance.start(args.toList()) { handed -> opens.deliver(handed) } }
    if (role == SingleInstance.Role.HANDED_OVER) {
        instance.close()
        exitProcess(0)
    }
    opens.install()
    val source = runBlocking { openDesktopSource() }
    val pending = PendingOpens()
    val prefs = PreferencesViewPrefs()
    // The names and colors people gave networks, beside the engine's own data.
    val visuals = NetworkVisuals(Path.of(dirs.data.toString(), "network-visuals.json"))
    // The first launch's own arguments are an open like any other; none, nothing to hand over (and no window to raise).
    if (args.isNotEmpty()) opens.deliver(args.toList())
    application {
        val closing = rememberCoroutineScope()
        // The engine starts behind the window; a start that fails leaves the window up to show the failed state.
        val lifecycle = remember { EngineLifecycle(source) { System.err.println("anydm: the engine could not start: ${it.message}") } }
        LaunchedEffect(Unit) { lifecycle.start() }
        // One stop, once a start has ended or has had its few seconds; every quit waits for it, and the app ends even if it throws.
        val quit: () -> Unit = {
            closing.launch {
                try {
                    lifecycle.stopOnce()
                } finally {
                    instance.close()
                    exitApplication()
                }
            }
        }
        val keys = remember { WindowKeys() }
        val windowState = rememberWindowState(width = 1280.dp, height = 800.dp)
        Window(
            onCloseRequest = quit,
            title = "anydm",
            icon = painterResource("icon.png"),
            state = windowState,
            onPreviewKeyEvent = keys::dispatch,
        ) {
            // The layout needs room for the list and the detail: the window can't be dragged smaller.
            DisposableEffect(window) {
                window.minimumSize = java.awt.Dimension(MIN_WINDOW_WIDTH, MIN_WINDOW_HEIGHT)
                onDispose {}
            }
            // What the app was opened with waits in `pending` until the window opens its dialog; a second launch raises the window.
            DisposableEffect(window) {
                opens.attach { handed ->
                    pending.offer(handed)
                    java.awt.EventQueue.invokeLater {
                        windowState.isMinimized = false
                        window.toFront()
                    }
                }
                onDispose {}
            }
            MenuBar {
                Menu("File") {
                    Item("Add…", shortcut = KeyShortcut(Key.N, ctrl = !IsMac, meta = IsMac), onClick = pending::openBlank)
                    Item("Quit", shortcut = KeyShortcut(Key.Q, ctrl = !IsMac, meta = IsMac), onClick = quit)
                }
            }
            App(source, prefs, keys, opens = pending, visuals = visuals)
        }
    }
}

// The packaged app's smoke test: its own engine, its own data folder, one download; the exit code is the answer.
private fun selfCheck(folder: String?): Int {
    val root = folder?.let { Path.of(it) } ?: Files.createTempDirectory("anydm-self-check")
    val problem =
        runBlocking {
            val source = openDesktopSource(SelfCheck.dirs(root))
            try {
                source.start()
                SelfCheck(source, root.resolve("downloads")).run()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // A module the runtime lacks shows up as an Error, not an exception.
                "${e::class.simpleName}: ${e.message}"
            } finally {
                runCatching { source.stop() }
            }
        }
    println(problem?.let { "self-check failed: $it" } ?: "self-check passed")
    println("launcher: ${System.getProperty("jpackage.app-path") ?: "none (not an installed app)"}")
    return if (problem == null) 0 else 1
}
