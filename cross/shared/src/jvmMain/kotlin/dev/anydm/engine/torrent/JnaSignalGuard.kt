package dev.anydm.engine.torrent

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import java.util.concurrent.ConcurrentHashMap

/**
 * [SignalGuard] on macOS and Linux through libc's `sigaction`. The handler records are kept as the opaque bytes
 * `sigaction` gives, so their layout, which differs between the two systems, doesn't matter; the handler is the first
 * pointer in both. The snapshot is process-wide: a second guard doesn't take a snapshot of what libtorrent left.
 */
class JnaSignalGuard : SignalGuard {
    private interface Libc : Library {
        fun sigaction(
            signal: Int,
            action: Pointer?,
            previous: Pointer?,
        ): Int
    }

    private val os = System.getProperty("os.name").lowercase()
    private val signals: List<Int> =
        when {
            "mac" in os -> listOf(SIGSEGV, SIGBUS_MAC)
            "linux" in os -> listOf(SIGSEGV, SIGBUS_LINUX)
            else -> emptyList()
        }
    private val libc: Libc? = if (signals.isEmpty()) null else Native.load("c", Libc::class.java)

    override fun snapshot() {
        val libc = libc ?: return
        for (signal in signals) {
            saved.computeIfAbsent(signal) { Memory(RECORD).also { record -> libc.sigaction(signal, null, record) } }
        }
    }

    override fun intact(): Boolean = signals.all { handlerNow(it) == handlerSaved(it) }

    override fun repair(): Boolean {
        val libc = libc ?: return false
        var repaired = false
        for (signal in signals) {
            val record = saved[signal] ?: continue
            if (handlerNow(signal) != record.getLong(0)) {
                libc.sigaction(signal, record, null)
                repaired = true
            }
        }
        return repaired
    }

    private fun handlerSaved(signal: Int): Long? = saved[signal]?.getLong(0)

    private fun handlerNow(signal: Int): Long? {
        val libc = libc ?: return null
        val now = Memory(RECORD)
        libc.sigaction(signal, null, now)
        return now.getLong(0)
    }

    private companion object {
        const val SIGSEGV = 11
        const val SIGBUS_MAC = 10
        const val SIGBUS_LINUX = 7

        // Room for the larger of the two layouts (Linux x86-64: 152 bytes).
        const val RECORD = 256L
        val saved = ConcurrentHashMap<Int, Memory>()
    }
}
