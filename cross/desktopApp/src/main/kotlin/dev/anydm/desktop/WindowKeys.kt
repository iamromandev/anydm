package dev.anydm.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalWindowInfo
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * The window's key shortcuts. The window hands every key to [dispatch] before any focused part sees it, so a shortcut keeps
 * working when focus was lost (a menu or dialog closed, the selection bar replaced the header); the screen on show sets
 * [handler] and takes it back when it leaves.
 *
 * A surface that takes text (a dialog with a field, a popover) calls [block] while it is shown: while any block is held the
 * shortcuts stand aside, so Ctrl/Cmd+V and A, Backspace and Delete reach the field in focus.
 */
class WindowKeys {
    @Volatile
    var handler: ((KeyEvent) -> Boolean)? = null

    private val blocks = AtomicInteger(0)
    private val held = ConcurrentHashMap.newKeySet<Key>()

    /** Whether the key [handler] is looking at is one that was already held down: an auto-repeat, not a new press. */
    @Volatile
    var repeating: Boolean = false
        private set

    /** Makes the shortcuts stand aside until the returned handle is closed; closing it more than once counts once. */
    fun block(): AutoCloseable {
        blocks.incrementAndGet()
        val released = AtomicBoolean(false)
        return AutoCloseable { if (released.compareAndSet(false, true)) blocks.decrementAndGet() }
    }

    /** Forgets every key held: the window lost focus, so the key-ups of keys held down will not arrive. */
    fun releaseAll() {
        held.clear()
        repeating = false
    }

    val blocked: Boolean get() = blocks.get() > 0

    fun dispatch(event: KeyEvent): Boolean {
        // Tracked even while blocked, so a key held across a dialog closing is not mistaken for a new press.
        when (event.type) {
            KeyEventType.KeyDown -> {
                repeating = !held.add(event.key)
            }

            KeyEventType.KeyUp -> {
                // macOS drops the key-up of a key pressed while Cmd is held; letting go of the modifier forgets what was held.
                if (event.key in MODIFIERS) held.clear() else held.remove(event.key)
                repeating = false
            }

            else -> {
                repeating = false
            }
        }
        if (blocked) return false
        return handler?.invoke(event) ?: false
    }
}

private val MODIFIERS = setOf(Key.MetaLeft, Key.MetaRight, Key.CtrlLeft, Key.CtrlRight)

/** Forgets the keys held whenever the window loses focus. */
@Composable
fun ReleaseKeysOnBlur(keys: WindowKeys) {
    val focused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(keys, focused) { if (!focused) keys.releaseAll() }
}

/** Holds a block on [keys] for as long as it is in the composition. */
@Composable
fun BlockWindowKeys(keys: WindowKeys) {
    DisposableEffect(keys) {
        val block = keys.block()
        onDispose { block.close() }
    }
}
