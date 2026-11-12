package dev.anydm.desktop

import java.util.concurrent.atomic.AtomicReference

/** A snapshot of a value as [SnapshotWriter.changed] saw it; [seq] grows with every change, so an older one is never written after a newer. */
class Snapshot<T>(
    val seq: Long,
    val value: T,
)

/**
 * Seq-checked saving of one kind of value. [changed] records every change as it happens (before any debounce): the latest is
 * what [takePending] hands the exit flush. [save] writes one at a time, skips a snapshot older than one already on disk and a
 * value equal to what the file already holds. [write] blocks on the disk: call these off the UI thread.
 */
class SnapshotWriter<T>(
    private val write: (T) -> SaveResult,
) {
    private val pending = AtomicReference<Snapshot<T>?>(null)
    private var counter = 0L

    /** Saves go one at a time; [lastWritten] (guarded by it) is the newest snapshot on disk, so a slow older save cannot undo a newer one. */
    private val writeLock = Any()
    private var lastWritten = 0L

    @Volatile
    private var saved: T? = null

    /** What the file already holds, so a state equal to it is never written again. */
    fun loaded(value: T?) {
        saved = value
    }

    @Synchronized
    fun changed(value: T): Snapshot<T> = Snapshot(++counter, value).also { pending.set(it) }

    fun save(change: Snapshot<T>): SaveResult =
        synchronized(writeLock) {
            val value = change.value
            if (change.seq <= lastWritten) {
                pending.compareAndSet(change, null)
                return SaveResult.Ok
            }
            if (value == saved) {
                lastWritten = change.seq
                pending.compareAndSet(change, null)
                return SaveResult.Ok
            }
            val result = write(value)
            if (result is SaveResult.Ok) {
                saved = value
                lastWritten = change.seq
                pending.compareAndSet(change, null)
            }
            result
        }

    /** The change still waiting for its debounce, if it differs from what is on disk; taking it clears it. */
    fun takePending(): Snapshot<T>? = pending.getAndSet(null)?.takeIf { it.value != saved }
}
