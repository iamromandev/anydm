package dev.anydm.desktop

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.anydm.engine.APP_NAME
import java.awt.Dimension

/** Saves a change that is still waiting for its debounce, then exits. The flush is capped, so a stuck disk cannot hold the exit. */
internal fun closeApp(
    stores: AppStores,
    exit: () -> Unit,
) {
    stores.flush()
    exit()
}

/** Quit from the Dock or Cmd+Q, a signal or the end of the last window skip onCloseRequest; a shutdown hook still runs. */
internal fun installExitFlush(
    stores: AppStores,
    addHook: (Thread) -> Unit = { Runtime.getRuntime().addShutdownHook(it) },
) {
    addHook(Thread({ stores.flush() }, "anydm-exit-flush"))
}

fun main() {
    val platform = Platform.current()
    val stores = AppStores.real(platform)
    installExitFlush(stores)
    application {
        // Also when the application scope is torn down some other way than the window's close button.
        DisposableEffect(stores) { onDispose { stores.flush() } }
        val bus = remember { CommandBus() }
        Window(
            onCloseRequest = { closeApp(stores, ::exitApplication) },
            title = APP_NAME,
            icon = painterResource("icon.png"),
            state = rememberWindowState(size = DpSize(1280.dp, 800.dp)),
        ) {
            window.minimumSize = Dimension(900, 600)
            val sample = if (System.getProperty("anydm.sample") == "empty") emptySample() else detailSample()
            AppMenuBar(bus, platform)
            HomeScreen(sample, isSystemInDarkTheme(), files = remember { FilesEnv.real() }, bus = bus, platform = platform, stores = stores)
        }
    }
}
