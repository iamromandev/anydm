package dev.anydm.engine.os

/** The only keys the hotkey cares about (OSI-06); the left and right of a modifier are one. Every other key is never passed on. */
enum class Key { SHIFT, CTRL, META, D }

/** A modifier set and a key: Cmd+Shift+D on macOS, Ctrl+Shift+D elsewhere. */
data class Chord(
    val modifiers: Set<Key>,
    val key: Key,
)

/**
 * Tells when the [chord] is pressed from key events (OSI-06). It acts when the key goes down while all the modifiers are
 * held, once for each press: a key already down that goes down again is the keyboard repeating, and does nothing, whatever
 * is pressed meanwhile, until it has been let go of. It remembers which of the four keys are down and nothing else, so what
 * is typed is not kept. Not thread-safe: the caller feeds it from one place at a time.
 */
class ChordDetector(
    private val chord: Chord,
) {
    private val held = mutableSetOf<Key>()

    /** [key] went down or up. True when this event completed the chord. */
    fun feed(
        key: Key,
        down: Boolean,
    ): Boolean {
        if (!down) {
            held -= key
            return false
        }
        if (!held.add(key)) return false
        return key == chord.key && held.containsAll(chord.modifiers)
    }

    /** Forgets what is held, as after the capture was stopped and started again. */
    fun reset() {
        held.clear()
    }
}
