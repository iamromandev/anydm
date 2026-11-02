package dev.anydm.desktop

import dev.anydm.engine.os.PairedBrowsers
import dev.anydm.engine.os.PairingState
import dev.anydm.engine.os.RevokeResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
internal class PairBrowserModelTest {
    private class Setup(
        val source: FakeSource,
        val model: PairBrowserModel,
        val scope: TestScope,
        /** The scope the model runs in: what the window drops with the dialog. */
        val dialog: CoroutineScope,
    ) {
        val state get() = model.state.value

        /** Run by the model's clock each time it reads it: a test uses it to make something happen at that very moment. */
        var onClock: () -> Unit = {}

        /** What the window does when the dialog goes: the model is closed, and the scope it ran in is dropped. */
        fun dialogGone(): Job {
            val cleanup = model.close()
            dialog.cancel()
            return cleanup
        }

        fun calls(what: String) = source.calls.count { it == what }

        /** Lets [seconds] of the clock pass: the model polls on it. */
        suspend fun pass(seconds: Int) {
            scope.advanceTimeBy(seconds.seconds)
            scope.runCurrent()
        }
    }

    private fun pairing(
        io: CoroutineDispatcher? = null,
        dialogDispatcher: ((TestScope) -> CoroutineDispatcher)? = null,
        block: suspend Setup.() -> Unit,
    ) = runTest(UnconfinedTestDispatcher()) {
        val source = FakeSource()
        val now = { Instant.fromEpochMilliseconds(10_000_000 + testScheduler.currentTime) }
        source.pairNow = now
        val dialog =
            CoroutineScope(
                SupervisorJob(backgroundScope.coroutineContext.job) +
                    (dialogDispatcher?.invoke(this) ?: UnconfinedTestDispatcher(testScheduler)),
            )
        lateinit var setup: Setup
        val clock = {
            setup.onClock()
            now()
        }
        val model = PairBrowserModel(source, dialog, clock, io ?: UnconfinedTestDispatcher(testScheduler))
        setup = Setup(source, model, this, dialog)
        setup.block()
    }

    @Test
    fun `generating shows the grouped code with two minutes to run`() =
        pairing {
            model.generate().join()
            val code = assertNotNull(state.code)
            assertEquals(DEFAULT_CODE, code.grouped)
            assertEquals(120, code.secondsLeft)
            assertFalse(code.throttled)
            assertNull(state.ended)
        }

    @Test
    fun `the countdown follows the clock and the state is asked about once a second`() =
        pairing {
            model.generate().join()
            pass(61)
            assertEquals(59, state.code?.secondsLeft)
            assertEquals(61, source.stateAsked)
        }

    @Test
    fun `too many wrong tries shows throttled and the code works again after`() =
        pairing {
            model.generate().join()
            source.pairState = PairingState.THROTTLED
            pass(2)
            assertTrue(assertNotNull(state.code).throttled)
            source.pairState = PairingState.WAITING
            pass(1)
            assertFalse(assertNotNull(state.code).throttled)
        }

    @Test
    fun `a used up code is taken off the screen and polling stops`() =
        pairing {
            model.generate().join()
            source.pairState = PairingState.BURNED
            pass(1)
            assertNull(state.code)
            assertEquals(CodeEnd.BURNED, state.ended)
            val asked = source.stateAsked
            pass(10)
            assertEquals(asked, source.stateAsked)
        }

    @Test
    fun `a code the engine calls expired is taken off the screen`() =
        pairing {
            model.generate().join()
            source.pairState = PairingState.EXPIRED
            pass(1)
            assertNull(state.code)
            assertEquals(CodeEnd.EXPIRED, state.ended)
        }

    @Test
    fun `a code past its time expires even when the engine still says waiting`() =
        pairing {
            source.pairLife = 3.seconds
            model.generate().join()
            pass(2)
            assertNotNull(state.code)
            pass(2)
            assertNull(state.code)
            assertEquals(CodeEnd.EXPIRED, state.ended)
        }

    @Test
    fun `pairing closes the code view and refreshes the list with a note naming the browser`() =
        pairing {
            source.pairedList = PairedBrowsers.Listed(listOf(browser("a", "Chrome")))
            model.load().join()
            model.generate().join()
            source.pairedList = PairedBrowsers.Listed(listOf(browser("a", "Chrome"), browser("b", "Firefox on Mac")))
            source.pairState = PairingState.PAIRED
            pass(1)
            assertNull(state.code)
            assertNull(state.ended)
            assertEquals("Paired Firefox on Mac", state.notice)
            assertEquals(listOf("a", "b"), (state.browsers as BrowserList.Listed).browsers.map { it.id })
        }

    @Test
    fun `generating again replaces the code and only the new one is watched`() =
        pairing {
            source.pairCodes += listOf("1111-1111-1111-1111", "2222-2222-2222-2222")
            model.generate().join()
            model.generate().join()
            assertEquals("2222-2222-2222-2222", state.code?.grouped)
            source.pairState = PairingState.BURNED
            pass(1)
            // One watcher: the first code's poll was ended, so one ask a second.
            assertEquals(1, source.stateAsked)
            assertEquals(CodeEnd.BURNED, state.ended)
        }

    @Test
    fun `generating a new code clears the note about the end of the last`() =
        pairing {
            model.generate().join()
            source.pairState = PairingState.BURNED
            pass(1)
            source.pairState = PairingState.WAITING
            model.generate().join()
            assertNull(state.ended)
            assertNotNull(state.code)
        }

    @Test
    fun `closing clears the code and stops asking`() =
        pairing {
            model.generate().join()
            pass(2)
            model.close()
            assertNull(state.code)
            val asked = source.stateAsked
            pass(10)
            assertEquals(asked, source.stateAsked)
            assertNull(state.code)
        }

    @Test
    fun `closing while a code is still being made leaves no code`() =
        pairing {
            source.pairGate = kotlinx.coroutines.CompletableDeferred()
            val job = model.generate()
            assertTrue(state.generating)
            model.close()
            source.pairGate?.complete(Unit)
            job.join()
            assertNull(state.code)
            assertFalse(state.generating)
        }

    @Test
    fun `a pairing that cannot start says so and shows no code`() =
        pairing {
            source.pairingThrows = IllegalStateException("the engine has stopped")
            model.generate().join()
            assertNull(state.code)
            assertNotNull(state.error)
            assertFalse(state.generating)
        }

    @Test
    fun `losing the code's state ends the code with an error instead of showing a stale one`() =
        pairing {
            model.generate().join()
            source.pairStateThrows = IllegalStateException("the engine has stopped")
            pass(1)
            assertNull(state.code)
            assertNotNull(state.error)
        }

    @Test
    fun `the list shows the paired browsers`() =
        pairing {
            source.pairedList = PairedBrowsers.Listed(listOf(browser("a", "Chrome")))
            assertEquals(BrowserList.Loading, state.browsers)
            model.load().join()
            assertEquals(listOf("a"), (state.browsers as BrowserList.Listed).browsers.map { it.id })
        }

    @Test
    fun `an empty list is nothing paired`() =
        pairing {
            model.load().join()
            assertEquals(BrowserList.Listed(emptyList()), state.browsers)
        }

    @Test
    fun `unreadable pairings are Unavailable never an empty list and Retry reads again`() =
        pairing {
            source.pairedList = PairedBrowsers.Unavailable
            model.load().join()
            assertEquals(BrowserList.Unavailable, state.browsers)
            source.pairedList = PairedBrowsers.Listed(listOf(browser("a")))
            model.load().join()
            assertIs<BrowserList.Listed>(state.browsers)
        }

    @Test
    fun `a failing read is Unavailable too`() =
        pairing {
            source.pairedThrows = IllegalStateException("the engine has stopped")
            model.load().join()
            assertEquals(BrowserList.Unavailable, state.browsers)
        }

    private suspend fun Setup.withTwo() {
        source.pairedList = PairedBrowsers.Listed(listOf(browser("a", "Chrome"), browser("b", "Firefox")))
        model.load().join()
    }

    private fun Setup.ids() = (state.browsers as BrowserList.Listed).browsers.map { it.id }

    @Test
    fun `asking to revoke revokes nothing until it is confirmed`() =
        pairing {
            withTwo()
            model.askRevoke("a")
            assertEquals("a", state.confirm?.id)
            assertFalse(source.calls.any { it.startsWith("revokeBrowser") })
            model.dismissRevoke()
            assertNull(state.confirm)
            assertFalse(source.calls.any { it.startsWith("revokeBrowser") })
        }

    @Test
    fun `confirming a revoke removes the row`() =
        pairing {
            withTwo()
            model.askRevoke("a")
            model.confirmRevoke().join()
            assertTrue("revokeBrowser a" in source.calls)
            assertEquals(listOf("b"), ids())
            assertNull(state.confirm)
            assertNull(state.error)
        }

    @Test
    fun `confirming with nothing asked does nothing`() =
        pairing {
            withTwo()
            model.confirmRevoke().join()
            assertFalse(source.calls.any { it.startsWith("revokeBrowser") })
        }

    @Test
    fun `a revoke that finds no such browser refreshes the list`() =
        pairing {
            withTwo()
            source.revokeResult = RevokeResult.NOT_FOUND
            source.pairedList = PairedBrowsers.Listed(listOf(browser("b", "Firefox")))
            model.askRevoke("a")
            model.confirmRevoke().join()
            assertEquals(listOf("b"), ids())
        }

    @Test
    fun `a revoke that could not be saved says so and reads the list again`() =
        pairing {
            withTwo()
            source.revokeResult = RevokeResult.NOT_SAVED
            model.askRevoke("a")
            model.confirmRevoke().join()
            assertNotNull(state.error)
            assertTrue(state.error!!.contains("could not be saved"), state.error)
            assertTrue(state.error!!.contains("the revoked browser may be accepted again after a restart"), state.error)
        }

    @Test
    fun `a revoke while the pairings are unreadable says so and keeps the row`() =
        pairing {
            withTwo()
            source.revokeResult = RevokeResult.STORE_UNAVAILABLE
            model.askRevoke("a")
            model.confirmRevoke().join()
            assertNotNull(state.error)
            assertEquals(listOf("a", "b"), ids())
        }

    @Test
    fun `the engine calls run off the thread the model was driven from`() {
        val executor = Executors.newSingleThreadExecutor { Thread(it, "pair-io-test") }
        try {
            pairing(io = executor.asCoroutineDispatcher()) {
                source.pairedList = PairedBrowsers.Listed(listOf(browser("a")))
                model.load().join()
                model.generate().join()
                // The first ask of the code's state, one poll later.
                pass(1)
                // Waited for off the executor, which the ask itself needs.
                val asked = withContext(Dispatchers.Default) { source.stateSeen.await(5, TimeUnit.SECONDS) }
                assertTrue(asked, "the state was never asked")
                model.askRevoke("a")
                model.confirmRevoke().join()
                model.close().join()
                assertTrue(source.pairThreads.all { it.startsWith("pair-io-test") }, source.pairThreads.toString())
                // pairedBrowsers, startPairing, state, revokeBrowser, cancelPairing; the model's clock runs on virtual time that moves
                // on while this waits, so a second ask of the state may come in too.
                assertTrue(source.pairThreads.size >= 5, source.pairThreads.toString() + source.calls)
            }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `the code is never in the state's text`() =
        pairing {
            model.generate().join()
            val text = state.toString()
            assertNotNull(state.code)
            assertFalse(text.contains("ABCD"), text)
            assertFalse(text.contains(DEFAULT_CODE.replace("-", "")), text)
        }

    @Test
    fun `a shown code does not print its code`() {
        val shown = ShownCode(DEFAULT_CODE, Instant.fromEpochSeconds(100), 90, throttled = true)
        val text = shown.toString()
        assertFalse(text.contains("ABCD"), text)
        assertFalse(text.contains(DEFAULT_CODE.replace("-", "")), text)
        assertFalse(shown.copy(secondsLeft = 3).toString().contains("EFGH"))
    }

    @Test
    fun `the engine side code does not print its code`() =
        runTest {
            val code = FakeSource().startPairing()
            assertEquals(DEFAULT_CODE, code.grouped)
            val text = code.toString()
            assertFalse(text.contains("ABCD"), text)
            assertFalse(text.contains(DEFAULT_CODE.replace("-", "")), text)
        }

    // ---- the code does not outlive the dialog ----

    @Test
    fun `closing ends the code in the engine and a second close does not ask again`() =
        pairing {
            model.generate().join()
            model.close().join()
            assertEquals(1, calls("cancelPairing"))
            assertEquals(PairingState.EXPIRED, source.lastIssued!!.state())
            model.close().join()
            assertEquals(1, calls("cancelPairing"))
        }

    @Test
    fun `closing when no code was ever asked for leaves the engine alone`() =
        pairing {
            model.load().join()
            model.close().join()
            assertEquals(0, calls("cancelPairing"))
        }

    @Test
    fun `the engine is told even when the dialog's scope is dropped right after the close`() =
        pairing(dialogDispatcher = { StandardTestDispatcher(it.testScheduler) }) {
            model.generate().join()
            dialogGone().join()
            assertEquals(1, calls("cancelPairing"))
            assertEquals(PairingState.EXPIRED, source.lastIssued!!.state())
        }

    @Test
    fun `closing while a call that makes a code is in flight ends that code too once it is made`() =
        pairing {
            source.pairGate = CompletableDeferred()
            source.pairGateHolds = true
            val shown = model.generate()
            val cleanup = model.close()
            // The engine is not asked while its own call is still making the code: it would end nothing and the new code would live.
            assertEquals(0, calls("cancelPairing"))
            source.pairGate?.complete(Unit)
            cleanup.join()
            shown.join()
            assertNull(state.code)
            assertEquals(1, calls("cancelPairing"))
            assertEquals(PairingState.EXPIRED, source.lastIssued!!.state())
        }

    @Test
    fun `a code that arrives just after the dialog closed is never shown and nothing watches it`() =
        pairing(dialogDispatcher = { StandardTestDispatcher(it.testScheduler) }) {
            // The close lands between the call returning and the code being put on show.
            onClock = {
                onClock = {}
                model.close()
            }
            // Driven from here, not from a join: the test would carry on in the middle of the model's own job.
            model.generate()
            scope.runCurrent()
            assertNull(state.code)
            assertTrue(state.closed)
            assertFalse(state.generating)
            val asked = source.stateAsked
            pass(10)
            assertEquals(asked, source.stateAsked)
            assertNull(state.code)
            assertEquals(1, calls("cancelPairing"), source.calls.toString())
            assertEquals(PairingState.EXPIRED, source.lastIssued!!.state())
        }

    @Test
    fun `a code that arrives after a newer one was asked for is not shown over it`() =
        pairing(dialogDispatcher = { StandardTestDispatcher(it.testScheduler) }) {
            source.pairCodes += listOf("1111-1111-1111-1111", "2222-2222-2222-2222")
            // The second ask lands between the first call returning and the first code being put on show.
            onClock = {
                onClock = {}
                source.pairGate = CompletableDeferred()
                model.generate()
            }
            model.generate()
            scope.runCurrent()
            assertNull(state.code)
            assertTrue(state.generating)
            source.pairGate?.complete(Unit)
            scope.runCurrent()
            assertEquals("2222-2222-2222-2222", state.code?.grouped)
            assertFalse(state.generating)
        }

    @Test
    fun `a closed model makes no more codes`() =
        pairing {
            model.close().join()
            model.generate().join()
            assertEquals(0, calls("startPairing"))
            assertNull(state.code)
            assertFalse(state.generating)
        }

    @Test
    fun `a code that could not be followed any more is ended in the engine`() =
        pairing {
            model.generate().join()
            source.pairStateThrows = IllegalStateException("the engine has stopped")
            pass(1)
            assertNull(state.code)
            assertEquals(1, calls("cancelPairing"))
        }

    @Test
    fun `a new code that could not be made ends the earlier one that is no longer on show`() =
        pairing {
            model.generate().join()
            source.pairingThrows = IllegalStateException("the engine has stopped")
            model.generate().join()
            assertNull(state.code)
            assertNotNull(state.error)
            assertEquals(1, calls("cancelPairing"))
            assertEquals(PairingState.EXPIRED, source.lastIssued!!.state())
        }

    // ---- asks are chained ----

    @Test
    fun `a newer ask waits for the older call to return before it asks the engine`() =
        pairing {
            source.pairGate = CompletableDeferred()
            source.pairGateHolds = true
            model.generate()
            val second = model.generate()
            assertEquals(1, calls("startPairing"), "the second call must wait for the first")
            source.pairGate?.complete(Unit)
            second.join()
            assertEquals(2, calls("startPairing"))
            assertNotNull(state.code)
            // The code on show is the newest one the engine made, and it is the one still good.
            assertEquals(PairingState.WAITING, source.lastIssued!!.state())
        }

    @Test
    fun `closing waits for the older call too and no ask starts after the close`() =
        pairing {
            source.pairGate = CompletableDeferred()
            source.pairGateHolds = true
            model.generate()
            model.generate()
            val cleanup = model.close()
            assertEquals(0, calls("cancelPairing"))
            source.pairGate?.complete(Unit)
            cleanup.join()
            assertEquals(1, calls("startPairing"), "the queued ask must not reach the engine after the close")
            assertEquals(1, calls("cancelPairing"))
            assertEquals(PairingState.EXPIRED, source.lastIssued!!.state())
            assertNull(state.code)
        }

    @Test
    fun `a failure of an older ask that a newer one replaced does not show an error or end the newer code`() =
        pairing {
            model.generate().join()
            // The state call of the first code fails at the very moment a newer code is asked for.
            source.pairStateThrows = IllegalStateException("gone")
            source.onState = {
                source.onState = {}
                model.generate()
            }
            pass(1)
            source.pairStateThrows = null
            assertNull(state.error)
            assertNotNull(state.code)
            assertEquals(0, calls("cancelPairing"))
            assertEquals(PairingState.WAITING, source.lastIssued!!.state())
        }

    @Test
    fun `closing ends the code it made and names it`() =
        pairing {
            model.generate().join()
            val made = source.lastIssued
            model.close().join()
            assertEquals(listOf(made), source.cancelledWith.toList())
        }

    @Test
    fun `a code that could not be followed is ended by naming it`() =
        pairing {
            model.generate().join()
            val made = source.lastIssued
            source.pairStateThrows = IllegalStateException("the engine has stopped")
            pass(1)
            assertEquals(listOf(made), source.cancelledWith.toList())
        }

    @Test
    fun `the browser list is still read when a new code is asked for just as the pairing succeeds`() =
        pairing {
            source.pairedList = PairedBrowsers.Listed(listOf(browser("a")))
            model.load().join()
            model.generate().join()
            source.pairedGate = CompletableDeferred()
            source.pairedList = PairedBrowsers.Listed(listOf(browser("a"), browser("b")))
            source.pairState = PairingState.PAIRED
            pass(1)
            // The read of the list is under way, held up; a new code is asked for meanwhile.
            model.generate()
            source.pairedGate?.complete(Unit)
            val listed = assertIs<BrowserList.Listed>(state.browsers)
            assertEquals(listOf("a", "b"), listed.browsers.map { it.id })
        }

    // ---- a confirmed revoke is not dropped ----

    @Test
    fun `a revoke in flight when the dialog goes still happens`() =
        pairing {
            withTwo()
            source.revokeGate = CompletableDeferred()
            model.askRevoke("a")
            val revoke = model.confirmRevoke()
            dialogGone()
            source.revokeGate?.complete(Unit)
            revoke.join()
            val left = (source.pairedList as PairedBrowsers.Listed).browsers.map { it.id }
            assertEquals(listOf("b"), left)
        }

    @Test
    fun `a revoke confirmed just before the dialog goes still happens`() =
        pairing(dialogDispatcher = { StandardTestDispatcher(it.testScheduler) }) {
            withTwo()
            model.askRevoke("a")
            // The dialog's scope is dropped before the revoke had a turn.
            val revoke = model.confirmRevoke()
            dialogGone()
            revoke.join()
            val left = (source.pairedList as PairedBrowsers.Listed).browsers.map { it.id }
            assertEquals(listOf("b"), left)
        }

    // ---- what the screen says ----

    @Test
    fun `pairing while the list was still loading names no browser it cannot tell apart`() =
        pairing {
            source.pairedList = PairedBrowsers.Listed(listOf(browser("a", "Chrome"), browser("b", "Firefox")))
            source.pairedGate = CompletableDeferred()
            model.load()
            assertEquals(BrowserList.Loading, state.browsers)
            model.generate().join()
            source.pairState = PairingState.PAIRED
            // Paired while the list is still being read: which of the two is new can't be told.
            pass(1)
            assertEquals(BrowserList.Loading, state.browsers)
            source.pairedGate?.complete(Unit)
            assertEquals("Paired a browser", state.notice)
            assertEquals(listOf("a", "b"), ids())
        }

    @Test
    fun `pairing while the list was unreadable names no browser either`() =
        pairing {
            source.pairedList = PairedBrowsers.Unavailable
            model.load().join()
            model.generate().join()
            source.pairedList = PairedBrowsers.Listed(listOf(browser("a", "Chrome"), browser("b", "Firefox")))
            source.pairState = PairingState.PAIRED
            pass(1)
            assertEquals("Paired a browser", state.notice)
        }

    private suspend fun Setup.paired() {
        model.load().join()
        model.generate().join()
        source.pairedList = PairedBrowsers.Listed(listOf(browser("a", "Chrome")))
        source.pairState = PairingState.PAIRED
        pass(1)
        assertEquals("Paired Chrome", state.notice)
    }

    @Test
    fun `the note about a pairing goes with the next code`() =
        pairing {
            paired()
            source.pairState = PairingState.WAITING
            model.generate().join()
            assertNull(state.notice)
        }

    @Test
    fun `the note about a pairing goes with the next revoke`() =
        pairing {
            paired()
            model.askRevoke("a")
            assertNull(state.notice)
        }

    @Test
    fun `the note about a pairing goes when the dialog closes`() =
        pairing {
            paired()
            model.close().join()
            assertNull(state.notice)
        }

    @Test
    fun `Retry clears the problem a revoke left`() =
        pairing {
            withTwo()
            source.revokeResult = RevokeResult.STORE_UNAVAILABLE
            model.askRevoke("a")
            model.confirmRevoke().join()
            assertNotNull(state.error)
            model.load().join()
            assertNull(state.error)
        }
}
