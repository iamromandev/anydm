package dev.anydm.desktop

import java.util.concurrent.CountDownLatch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AppStoresDownloadsTest {
    private val settings = SettingsFile(theme = ThemeChoice.Dark)
    private val list = DownloadsFile(downloads = listOf(DownloadDto("1", "https://x.test/a", "a", null, 1, 0, DownloadStatus.Paused)))

    @Test
    fun flushWritesAPendingDownloadsChangeNextToASettingsOne() {
        val store = InMemoryDownloadsStore()
        val s = AppStores(InMemorySettingsStore(), InMemorySecretStore(), 2_000, store)
        s.downloadsChanged(list)
        s.changed(settings)
        s.flush()
        assertEquals(listOf(list), store.saves)
    }

    @Test
    fun aDownloadsChangeAlreadySavedIsNotWrittenAgainByFlush() {
        val store = InMemoryDownloadsStore()
        val s = AppStores(InMemorySettingsStore(), InMemorySecretStore(), 2_000, store)
        s.saveDownloads(s.downloadsChanged(list))
        s.flush()
        assertEquals(1, store.saves.size)
    }

    @Test
    fun aStateEqualToTheLoadedDownloadsIsNotWritten() {
        val store = InMemoryDownloadsStore()
        val s = AppStores(InMemorySettingsStore(), InMemorySecretStore(), 2_000, store)
        s.downloadsLoaded(list)
        s.saveDownloads(s.downloadsChanged(list))
        assertTrue(store.saves.isEmpty())
    }

    @Test
    fun anOlderDownloadsSnapshotIsNeverWrittenAfterANewerOne() {
        val store = InMemoryDownloadsStore()
        val s = AppStores(InMemorySettingsStore(), InMemorySecretStore(), 2_000, store)
        val older = s.downloadsChanged(list)
        val newer = s.downloadsChanged(list.copy(downloads = emptyList()))
        s.saveDownloads(newer)
        s.saveDownloads(older)
        assertEquals(listOf(list.copy(downloads = emptyList())), store.saves)
    }

    @Test
    fun aStuckDownloadsDiskDoesNotHoldTheExitBeyondTheOneCap() {
        val gate = CountDownLatch(1)
        val stuck =
            object : DownloadsStore {
                override fun load(): DownloadsLoad = JsonLoad.Missing

                override fun save(file: DownloadsFile): SaveResult {
                    gate.await()
                    return SaveResult.Ok
                }
            }
        val s = AppStores(InMemorySettingsStore(), InMemorySecretStore(), 300, stuck)
        s.downloadsChanged(list)
        s.changed(settings)
        val start = System.nanoTime()
        s.flush()
        gate.countDown()
        assertTrue((System.nanoTime() - start) / 1_000_000 < 1_500, "flush must stop at its cap")
    }
}
