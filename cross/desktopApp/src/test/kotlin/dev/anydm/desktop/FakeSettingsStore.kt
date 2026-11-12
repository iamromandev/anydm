package dev.anydm.desktop

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** A settings store in memory with a thread check: a call from a thread in [forbidden] (the UI thread) is counted. */
class FakeSettingsStore(
    @Volatile var loadResult: LoadResult = LoadResult.Missing,
    @Volatile var saveResult: SaveResult = SaveResult.Ok,
    /** When set, [save] waits for it (up to [blockFor] ms) before returning, like a stuck disk. */
    val gate: CountDownLatch? = null,
    private val blockFor: Long = 10_000,
) : SettingsStore {
    val saves = CopyOnWriteArrayList<SettingsFile>()
    val loads = CopyOnWriteArrayList<String>()
    val saveThreads = CopyOnWriteArrayList<String>()
    val forbidden = CopyOnWriteArrayList<Thread>()

    @Volatile
    var onForbiddenThread = 0

    private fun check() {
        if (Thread.currentThread() in forbidden) onForbiddenThread++
    }

    override fun load(): LoadResult {
        check()
        loads.add(Thread.currentThread().name)
        return loadResult
    }

    override fun save(file: SettingsFile): SaveResult {
        check()
        saveThreads.add(Thread.currentThread().name)
        gate?.await(blockFor, TimeUnit.MILLISECONDS)
        saves.add(file)
        return saveResult
    }
}
