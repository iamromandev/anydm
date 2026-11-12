package dev.anydm.desktop

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AppStoresTest {
    private val a = SettingsFile(theme = ThemeChoice.Dark)
    private val b = SettingsFile(theme = ThemeChoice.Light)

    private fun stores(
        settings: SettingsStore,
        timeout: Long = 2_000,
    ) = AppStores(settings, InMemorySecretStore(), timeout)

    @Test
    fun flushWritesAChangeThatIsStillWaitingForItsDebounce() {
        val store = FakeSettingsStore()
        val s = stores(store)
        s.changed(a)
        s.flush()
        assertEquals(listOf(a), store.saves)
    }

    @Test
    fun flushWritesOnlyTheLatestChange() {
        val store = FakeSettingsStore()
        val s = stores(store)
        s.changed(a)
        s.changed(b)
        s.flush()
        assertEquals(listOf(b), store.saves)
    }

    @Test
    fun flushWithNothingPendingWritesNothing() {
        val store = FakeSettingsStore()
        stores(store).flush()
        assertTrue(store.saves.isEmpty())
    }

    @Test
    fun aChangeThatWasAlreadySavedIsNotWrittenAgainByFlush() {
        val store = FakeSettingsStore()
        val s = stores(store)
        s.save(s.changed(a))
        s.flush()
        assertEquals(listOf(a), store.saves)
    }

    @Test
    fun aStateEqualToTheLoadedFileIsNotWritten() {
        val store = FakeSettingsStore()
        val s = stores(store)
        s.loaded(a)
        assertEquals(SaveResult.Ok, s.save(s.changed(a)))
        s.flush()
        assertTrue(store.saves.isEmpty())
    }

    @Test
    fun aFailedSaveStaysPendingSoFlushTriesAgain() {
        val store = FakeSettingsStore(saveResult = SaveResult.Failed("disk"))
        val s = stores(store)
        assertIs<SaveResult.Failed>(s.save(s.changed(a)))
        store.saveResult = SaveResult.Ok
        s.flush()
        assertEquals(listOf(a, a), store.saves)
    }

    @Test
    fun flushGivesUpAfterTheTimeoutOnAStuckDisk() {
        val gate = CountDownLatch(1)
        val store = FakeSettingsStore(gate = gate)
        val s = stores(store, timeout = 150)
        s.changed(a)
        val start = System.nanoTime()
        s.flush()
        val ms = (System.nanoTime() - start) / 1_000_000
        gate.countDown()
        assertTrue(ms in 100..1_500, "took $ms ms")
    }

    @Test
    fun flushNeverThrowsWhenTheStoreDoes() {
        val s =
            stores(
                object : SettingsStore {
                    override fun load() = LoadResult.Missing

                    override fun save(file: SettingsFile): SaveResult = error("disk on fire")
                },
            )
        s.changed(a)
        s.flush()
    }

    @Test
    fun closingTheAppFlushesBeforeExiting() {
        val store = FakeSettingsStore()
        val s = stores(store)
        s.changed(a)
        val order = mutableListOf<String>()
        closeApp(s) { order += "exit${store.saves.size}" }
        assertEquals(listOf("exit1"), order)
    }

    @Test
    fun closingTheAppStillExitsWhenTheDiskIsStuck() {
        val gate = CountDownLatch(1)
        val s = stores(FakeSettingsStore(gate = gate), timeout = 100)
        s.changed(a)
        var exited = false
        closeApp(s) { exited = true }
        gate.countDown()
        assertTrue(exited)
    }

    @Test
    fun theDefaultStoresAreInMemory() {
        val s = AppStores.inMemory()
        assertIs<InMemorySettingsStore>(s.settings)
        assertIs<InMemorySecretStore>(s.secrets)
    }

    /** A disk that blocks its first write until [gate] opens; [written] is the order the writes finished in, so the last is the file. */
    private class SlowFirstStore(
        val gate: CountDownLatch,
    ) : SettingsStore {
        val written = CopyOnWriteArrayList<SettingsFile>()
        val entered = CountDownLatch(1)
        private val calls = AtomicInteger()

        override fun load() = LoadResult.Missing

        override fun save(file: SettingsFile): SaveResult {
            if (calls.getAndIncrement() == 0) {
                entered.countDown()
                gate.await(5, TimeUnit.SECONDS)
            }
            written += file
            return SaveResult.Ok
        }
    }

    // S1: an older snapshot never overwrites a newer one

    @Test
    fun aDebouncedSaveThatIsStillRunningCannotOverwriteTheNewerFlush() {
        val gate = CountDownLatch(1)
        val disk = SlowFirstStore(gate)
        val s = stores(disk, timeout = 3_000)
        val older = s.changed(a)
        val saving = thread { s.save(older) }
        assertTrue(disk.entered.await(2, TimeUnit.SECONDS))
        s.changed(b)
        val flushing = thread { s.flush() }
        Thread.sleep(80)
        gate.countDown()
        saving.join(3_000)
        flushing.join(3_000)
        assertEquals(listOf(a, b), disk.written.toList())
    }

    @Test
    fun anOlderSnapshotThatArrivesAfterANewerOneWasWrittenIsSkipped() {
        val store = FakeSettingsStore()
        val s = stores(store)
        val older = s.changed(a)
        val newer = s.changed(b)
        assertEquals(SaveResult.Ok, s.save(newer))
        assertEquals(SaveResult.Ok, s.save(older))
        assertEquals(listOf(b), store.saves.toList())
    }

    @Test
    fun aFailedNewerSaveDoesNotStopAnOlderOneFromBeingTriedLater() {
        val store = FakeSettingsStore(saveResult = SaveResult.Failed("disk"))
        val s = stores(store)
        val older = s.changed(a)
        val newer = s.changed(b)
        assertIs<SaveResult.Failed>(s.save(newer))
        store.saveResult = SaveResult.Ok
        assertEquals(SaveResult.Ok, s.save(older))
        assertEquals(listOf(b, a), store.saves.toList(), "nothing newer is on disk, so the older one is still the best there is")
    }

    @Test
    fun flushWaitsForARunningSaveButNotLongerThanItsTimeout() {
        val gate = CountDownLatch(1)
        val disk = SlowFirstStore(gate)
        val s = stores(disk, timeout = 200)
        val older = s.changed(a)
        val saving = thread { s.save(older) }
        assertTrue(disk.entered.await(2, TimeUnit.SECONDS))
        s.changed(b)
        val start = System.nanoTime()
        s.flush()
        val ms = (System.nanoTime() - start) / 1_000_000
        gate.countDown()
        saving.join(3_000)
        assertTrue(ms in 150..1_500, "took $ms ms")
    }

    // S2: flush runs once, however many exits ask for it, and waits for the keychain

    @Test
    fun flushIsIdempotent() {
        val store = FakeSettingsStore()
        val s = stores(store)
        s.changed(a)
        s.flush()
        s.flush()
        assertEquals(listOf(a), store.saves.toList())
    }

    @Test
    fun aSecondFlushWaitsForTheFirstInsteadOfReturningEarly() {
        val gate = CountDownLatch(1)
        val disk = SlowFirstStore(gate)
        val s = stores(disk, timeout = 3_000)
        s.changed(a)
        val first = thread { s.flush() }
        assertTrue(disk.entered.await(2, TimeUnit.SECONDS))
        thread {
            Thread.sleep(250)
            gate.countDown()
        }
        val start = System.nanoTime()
        s.flush()
        val ms = (System.nanoTime() - start) / 1_000_000
        first.join(3_000)
        assertTrue(ms in 200..2_000, "took $ms ms")
        assertEquals(listOf(a), disk.written.toList())
    }

    @Test
    fun flushWaitsForAKeychainWriteThatIsStillRunning() {
        val s = stores(FakeSettingsStore(), timeout = 3_000)
        val finished = s.secretWriteStarted()
        thread {
            Thread.sleep(250)
            finished()
        }
        val start = System.nanoTime()
        s.flush()
        val ms = (System.nanoTime() - start) / 1_000_000
        assertTrue(ms in 200..2_000, "took $ms ms")
    }

    @Test
    fun flushGivesUpOnAKeychainWriteThatNeverEnds() {
        val s = stores(FakeSettingsStore(), timeout = 150)
        s.secretWriteStarted()
        val start = System.nanoTime()
        s.flush()
        val ms = (System.nanoTime() - start) / 1_000_000
        assertTrue(ms in 100..1_500, "took $ms ms")
    }

    @Test
    fun aKeychainWriteThatEndedDoesNotDelayTheFlush() {
        val s = stores(FakeSettingsStore(), timeout = 3_000)
        s.secretWriteStarted()()
        val start = System.nanoTime()
        s.flush()
        val ms = (System.nanoTime() - start) / 1_000_000
        assertTrue(ms < 1_000, "took $ms ms")
    }

    private class ClosableSecrets :
        SecretStore,
        AutoCloseable {
        val closed = AtomicInteger()

        override suspend fun get(account: String): SecretResult = SecretResult.Ok(null)

        override suspend fun set(
            account: String,
            secret: String,
        ): SecretResult = SecretResult.Ok(null)

        override suspend fun delete(account: String): SecretResult = SecretResult.Ok(null)

        override fun close() {
            closed.incrementAndGet()
        }
    }

    @Test
    fun theKeychainIsClosedOnceAfterTheFlush() {
        val secrets = ClosableSecrets()
        val s = AppStores(FakeSettingsStore(), secrets, 500)
        s.flush()
        s.flush()
        assertEquals(1, secrets.closed.get())
    }

    @Test
    fun theKeychainIsNotClosedUnderAWriteThatIsStillRunning() {
        val secrets = ClosableSecrets()
        val s = AppStores(FakeSettingsStore(), secrets, 100)
        s.secretWriteStarted()
        s.flush()
        assertEquals(0, secrets.closed.get())
    }

    // S2: every way out of the app

    @Test
    fun theShutdownHookFlushesAPendingChange() {
        val store = FakeSettingsStore()
        val s = stores(store)
        var hook: Thread? = null
        installExitFlush(s) { hook = it }
        s.changed(a)
        assertTrue(store.saves.isEmpty(), "installing the hook writes nothing")
        hook?.run() ?: error("no hook was added")
        assertEquals(listOf(a), store.saves.toList())
    }

    @Test
    fun theHookAfterTheCloseRequestWritesNothingAgain() {
        val store = FakeSettingsStore()
        val s = stores(store)
        var hook: Thread? = null
        installExitFlush(s) { hook = it }
        s.changed(a)
        closeApp(s) {}
        hook?.run()
        assertEquals(listOf(a), store.saves.toList())
    }

    @Test
    fun theHookWaitsForAKeychainWriteToo() {
        val s = stores(FakeSettingsStore(), timeout = 3_000)
        var hook: Thread? = null
        installExitFlush(s) { hook = it }
        val finished = s.secretWriteStarted()
        thread {
            Thread.sleep(250)
            finished()
        }
        val start = System.nanoTime()
        hook?.run()
        val ms = (System.nanoTime() - start) / 1_000_000
        assertTrue(ms in 200..2_500, "took $ms ms")
    }
}
