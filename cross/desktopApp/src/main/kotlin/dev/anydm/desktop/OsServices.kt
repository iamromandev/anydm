package dev.anydm.desktop

import dev.anydm.engine.Engine
import dev.anydm.engine.log.Log
import dev.anydm.engine.os.BridgeServer
import dev.anydm.engine.os.ClipboardLink
import dev.anydm.engine.os.ClipboardWatch
import dev.anydm.engine.os.GlobalHotkey
import dev.anydm.engine.os.HotkeyService
import dev.anydm.engine.os.HotkeyStatus
import dev.anydm.engine.os.NotificationRelay
import dev.anydm.engine.os.nativeGlobalHotkey
import dev.anydm.engine.os.secureBridgeService
import dev.anydm.engine.os.systemClipboard
import dev.anydm.engine.platform.AppDirs
import dev.anydm.engine.platform.ProcessRunner
import dev.anydm.engine.platform.notifierForCurrentOs
import dev.anydm.engine.store.Settings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * The desktop's OS services beside the engine (#610): notifications for finished, failed and allowance-reached jobs, the
 * clipboard watch, the global hotkey and the browser bridge. Each follows its setting in the engine, so a switch in Settings
 * takes effect at once; one that can't start is a status for the window to explain, never a reason to stop the app.
 */
internal class OsServices(
    private val engine: Engine,
    private val runner: ProcessRunner,
    dirs: AppDirs,
    private val log: Log,
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val copied = MutableSharedFlow<ClipboardLink>(extraBufferCapacity = 16)
    private val pressed = MutableSharedFlow<String>(extraBufferCapacity = 4)
    private val bridgeState = MutableStateFlow<BridgeStatus>(BridgeStatus.Off)
    private val clipboard = systemClipboard()

    @Volatile
    private var bridgeServer: BridgeServer? = null

    val clipboardLinks: Flow<ClipboardLink> = copied.asSharedFlow()
    val hotkeyPresses: Flow<String> = pressed.asSharedFlow()
    val bridge: StateFlow<BridgeStatus> = bridgeState

    private val hotkey =
        HotkeyService(hotkeyFor(File(dirs.data.toString(), "natives"), log), log) {
            // The text on the clipboard goes to the add review, which reads it like a pasted link.
            pressed.emit(runCatching { clipboard.text() }.getOrNull().orEmpty())
        }
    val hotkeyStatus: StateFlow<HotkeyStatus> = hotkey.status

    /** Starts the services; once the engine is running, so the bridge and the relay never talk to one that is not. */
    fun start() {
        NotificationRelay(
            events = engine.events,
            describe = { id ->
                engine.downloads.value
                    .firstOrNull { it.download.id == id }
                    ?.let { NotificationRelay.Described(displayName(it), it.download.kind) }
            },
            // An engine that can't be asked has nothing to tell.
            enabled = { runCatching { engine.runtimeSettings().notifications }.getOrDefault(false) },
            notifier = notifierForCurrentOs(runner),
            log = log,
        ).start(scope)
        val watch = ClipboardWatch(clipboard)
        scope.launch { watch.links.collect { copied.emit(it) } }
        scope.launch { watch.run(engine.osSettings.map { it.clipboardWatch }) }
        scope.launch { hotkey.run(engine.osSettings.map { it.globalHotkey }) }
        scope.launch { startBridge() }
    }

    // The bridge answers the extension on a loopback port; no free port is a status, not a failure of the app.
    private fun startBridge() {
        try {
            val server = BridgeServer(secureBridgeService(engine), origins = ::allowedOrigins)
            val port = server.start()
            if (scope.isActive) {
                bridgeServer = server
                bridgeState.value = BridgeStatus.Listening(port)
            } else {
                // Closed while binding.
                server.close()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            bridgeState.value = BridgeStatus.Failed(e.message ?: e::class.simpleName ?: "unknown error")
        }
    }

    // An engine that can't be asked answers only the extension anydm ships: never "any extension".
    private fun allowedOrigins(): Set<String> =
        try {
            runBlocking { engine.runtimeSettings().bridgeOrigins }
        } catch (_: Exception) {
            setOf(Settings.CHROME_EXTENSION_ORIGIN)
        }

    override fun close() {
        scope.cancel()
        bridgeServer?.close()
        bridgeServer = null
        bridgeState.value = BridgeStatus.Off
    }

    private companion object {
        // A hotkey library that can't be set up on this machine is a status the window explains.
        fun hotkeyFor(
            folder: File,
            log: Log,
        ): GlobalHotkey =
            try {
                nativeGlobalHotkey(folder, log)
            } catch (e: Exception) {
                unsupported(e.message ?: "the hotkey library couldn't be loaded")
            } catch (e: LinkageError) {
                unsupported(e.message ?: "the hotkey library couldn't be loaded")
            }

        fun unsupported(reason: String) =
            object : GlobalHotkey {
                override suspend fun start(onPress: () -> Unit): HotkeyStatus = HotkeyStatus.Unsupported(reason)

                override suspend fun stop() = Unit
            }
    }
}
