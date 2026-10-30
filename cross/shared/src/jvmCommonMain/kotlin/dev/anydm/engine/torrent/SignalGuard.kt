package dev.anydm.engine.torrent

/**
 * Keeps the JVM's SIGSEGV and SIGBUS handlers in place. The JVM catches those signals itself, to throw a
 * NullPointerException from compiled code and to stop threads at a safepoint, and libtorrent installs its own handlers
 * for them and never puts the JVM's back: the next signal then ends the process. [snapshot] is called before libtorrent
 * loads, while the handlers are still the JVM's; [repair] puts them back.
 */
interface SignalGuard {
    /** Remembers the handlers now in place, once for the process: later calls keep the first snapshot. */
    fun snapshot()

    /** Whether the handlers are still the ones [snapshot] found. */
    fun intact(): Boolean

    /** Puts the snapshot's handlers back where they changed; true when any did. */
    fun repair(): Boolean

    /** For systems where the JVM and libtorrent don't clash, or nothing can be done. */
    data object None : SignalGuard {
        override fun snapshot() = Unit

        override fun intact() = true

        override fun repair() = false
    }
}
