package dev.anydm.engine.os

import com.github.kwhat.jnativehook.GlobalScreen
import com.github.kwhat.jnativehook.NativeHookException
import com.github.kwhat.jnativehook.keyboard.NativeKeyEvent
import com.github.kwhat.jnativehook.keyboard.NativeKeyListener
import dev.anydm.engine.log.Log
import dev.anydm.engine.platform.DesktopOs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.logging.Level
import java.util.logging.Logger

/** The key capture library, as little of it as the hotkey needs: key events of four keys in, nothing else out. */
internal interface NativeHook {
    /** Starts the capture. [onKey] gets each press and release of the four keys of [Key], and no other key. Throws if it can't. */
    fun register(onKey: (Key, Boolean) -> Unit)

    fun unregister()
}

/** The four keys, or null for any other key, so what is typed never gets past this point. */
internal fun keyOf(nativeCode: Int): Key? =
    when (nativeCode) {
        NativeKeyEvent.VC_SHIFT -> Key.SHIFT
        NativeKeyEvent.VC_CONTROL -> Key.CTRL
        NativeKeyEvent.VC_META -> Key.META
        NativeKeyEvent.VC_D -> Key.D
        else -> null
    }

/** The listener the library is given: it turns a native event into a [Key] and passes on that, or nothing for any other key. */
internal fun listenerFor(onKey: (Key, Boolean) -> Unit): NativeKeyListener =
    object : NativeKeyListener {
        override fun nativeKeyPressed(event: NativeKeyEvent) {
            keyOf(event.keyCode)?.let { onKey(it, true) }
        }

        override fun nativeKeyReleased(event: NativeKeyEvent) {
            keyOf(event.keyCode)?.let { onKey(it, false) }
        }
    }

/**
 * [NativeHook] over JNativeHook (LGPL, an unmodified jar). The only code that touches the library. The library unpacks its
 * native file next to its own jar unless told where, which in an installed app is a folder the user can't write to (or one
 * whose signature a new file would break); [libraryFolder] says where, and must be a folder of the user's own, never a shared
 * one such as the temp folder, since whatever file is there gets loaded. Its hook thread is not a daemon: the process
 * cannot end while the hook is registered, so the hotkey must be stopped before the app exits.
 */
internal class JNativeHookApi(
    private val libraryFolder: File,
) : NativeHook {
    private var listener: NativeKeyListener? = null

    // Kept, so the logger configured below is not collected before the library asks for it.
    private val logger = Logger.getLogger(GlobalScreen::class.java.`package`.name)

    @Synchronized
    override fun register(onKey: (Key, Boolean) -> Unit) {
        // The library logs every key event it handles; this would be the keyboard in the log.
        logger.level = Level.OFF
        logger.useParentHandlers = false
        useLibraryFolder(libraryFolder)
        try {
            GlobalScreen.registerNativeHook()
        } catch (e: LinkageError) {
            // A native file cut short by an interrupted unpacking would be loaded again and fail again for ever.
            libraryFolder.listFiles()?.filter { it.name.contains("JNativeHook", ignoreCase = true) }?.forEach { it.delete() }
            throw e
        }
        val added = listenerFor(onKey)
        GlobalScreen.addNativeKeyListener(added)
        listener = added
    }

    @Synchronized
    override fun unregister() {
        listener?.let { GlobalScreen.removeNativeKeyListener(it) }
        listener = null
        if (GlobalScreen.isNativeHookRegistered()) GlobalScreen.unregisterNativeHook()
    }

    internal companion object {
        /** Tells the library where to unpack its native file; before the first use of [GlobalScreen], which reads it once. */
        fun useLibraryFolder(folder: File) {
            try {
                Files.createDirectories(folder.toPath(), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
            } catch (_: UnsupportedOperationException) {
                folder.mkdirs()
            }
            System.setProperty("jnativehook.lib.path", folder.path)
        }
    }
}

/**
 * The global hotkey on the desktop (OSI-06): Cmd+Shift+D on macOS, Ctrl+Shift+D elsewhere. A [ChordDetector] sees the four
 * keys and acts once per press. Where the system can't do it, [start] says so instead of trying: Linux under Wayland (it
 * blocks global key capture) or without an X server. A capture the library refuses is, on macOS with the library's
 * accessibility codes, the permission not given yet; a native library that isn't there is an unsupported system. None of it
 * throws. Registering and unregistering wait for the system (a hook that is slow to come up, a dead X server), so they run off
 * the caller's thread. After [HotkeyStatus.NeedsPermission] nothing retries by itself: the setting is turned off and on again.
 */
internal class NativeHotkey(
    private val hook: NativeHook,
    private val os: DesktopOs = DesktopOs.of(System.getProperty("os.name")),
    private val env: Map<String, String> = System.getenv(),
    private val log: Log = Log.None,
) : GlobalHotkey {
    private val chord = if (os == DesktopOs.MACOS) Chord(setOf(Key.SHIFT, Key.META), Key.D) else Chord(setOf(Key.SHIFT, Key.CTRL), Key.D)
    private val detector = ChordDetector(chord)
    private val lock = Mutex()
    private var running = false

    @Volatile private var pressed: (() -> Unit)? = null

    override suspend fun start(onPress: () -> Unit): HotkeyStatus = lock.withLock { withContext(Dispatchers.IO) { begin(onPress) } }

    override suspend fun stop() = lock.withLock { withContext(NonCancellable + Dispatchers.IO) { end() } }

    private fun begin(onPress: () -> Unit): HotkeyStatus {
        unsupportedReason()?.let { return HotkeyStatus.Unsupported(it) }
        if (running) return HotkeyStatus.Active
        synchronized(detector) { detector.reset() }
        pressed = onPress
        return try {
            hook.register { key, down -> if (synchronized(detector) { detector.feed(key, down) }) pressed?.invoke() }
            running = true
            HotkeyStatus.Active
        } catch (e: NativeHookException) {
            pressed = null
            // The library's own codes: on macOS 64 is the Accessibility permission, 65 the event tap it is needed for.
            val permission = e.code == NativeHookException.DARWIN_AXAPI_DISABLED || e.code == NativeHookException.DARWIN_CREATE_EVENT_PORT
            if (os == DesktopOs.MACOS &&
                permission
            ) {
                HotkeyStatus.NeedsPermission
            } else {
                HotkeyStatus.Failed(e.message ?: "the key capture was refused (code ${e.code})")
            }
        } catch (e: LinkageError) {
            // The native library isn't there for this system, or could not be unpacked (UnsatisfiedLinkError, a failed initializer).
            pressed = null
            log.warn("hotkey") { "the key capture library could not be loaded: ${e.message}" }
            HotkeyStatus.Unsupported("no key capture library for this system: ${e.message?.take(120)}")
        } catch (e: Exception) {
            pressed = null
            HotkeyStatus.Failed(e.message ?: e::class.simpleName ?: "failed")
        }
    }

    private fun end() {
        if (!running) return
        running = false
        pressed = null
        try {
            hook.unregister()
        } catch (_: Throwable) {
            // Letting go can't be made to work any harder than this.
        }
        synchronized(detector) { detector.reset() }
    }

    private fun unsupportedReason(): String? =
        when {
            os != DesktopOs.LINUX -> {
                null
            }

            env["XDG_SESSION_TYPE"].equals("wayland", ignoreCase = true) || !env["WAYLAND_DISPLAY"].isNullOrBlank() -> {
                "Wayland does not allow global key capture; use an X11 session"
            }

            env["DISPLAY"].isNullOrBlank() -> {
                "no X server to capture keys from"
            }

            else -> {
                null
            }
        }
}

/**
 * The global hotkey of this machine (OSI-06), for [HotkeyService]. [libraryFolder] is where the capture library unpacks its
 * native file: a folder of the app's own under the user's data folder. The hook keeps the process alive, so the service must
 * be cancelled (which stops the hook) before the app exits.
 */
fun nativeGlobalHotkey(
    libraryFolder: File,
    log: Log = Log.None,
): GlobalHotkey = NativeHotkey(JNativeHookApi(libraryFolder), log = log)
