package dev.anydm.desktop

import dev.anydm.engine.TorrentActionResult
import dev.anydm.engine.TorrentDetailResult
import dev.anydm.engine.core.DownloadId
import dev.anydm.engine.torrent.PieceMap
import dev.anydm.engine.torrent.TorrentDetail
import dev.anydm.engine.torrent.TorrentTracker
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
internal class TrackersModelTest {
    private val id = DownloadId(1)

    private fun tracker(
        url: String,
        custom: Boolean = false,
        tier: Int = 0,
    ) = TorrentTracker(url, tier, verified = true, enabled = true, current = false, custom = custom)

    private fun detail(
        vararg trackers: TorrentTracker,
        private: Boolean = false,
    ) = TorrentDetailResult.Detail(TorrentDetail(emptyList(), trackers.toList(), PieceMap.Empty, private))

    private fun model(
        result: TorrentDetailResult,
        block: suspend TestScope.(FakeSource, TrackersModel) -> Unit,
    ) = runTest(UnconfinedTestDispatcher()) {
        val source = FakeSource()
        source.torrent = result
        block(source, TrackersModel(source, id, backgroundScope))
    }

    // Parsing

    @Test
    fun `lines with a tracker scheme are kept, the others counted`() {
        val parsed =
            parseTrackers(
                " udp://a.example:6969/announce \n\nhttp://b.example/announce\nHTTPS://c.example/a\nws://d\nwss://e/x\nftp://f\nnot a link\nudp://\n",
                emptyList(),
            )
        assertEquals(
            listOf("udp://a.example:6969/announce", "http://b.example/announce", "HTTPS://c.example/a", "ws://d", "wss://e/x"),
            parsed.fresh,
        )
        assertEquals(3, parsed.invalid)
        assertFalse(parsed.empty)
    }

    @Test
    fun `a blank input parses as empty`() {
        val parsed = parseTrackers("  \n \r\n", emptyList())
        assertTrue(parsed.empty)
        assertEquals(emptyList(), parsed.fresh)
    }

    @Test
    fun `repeated lines and ones already listed are not added again`() {
        val parsed = parseTrackers("udp://a/x\nudp://a/x\nudp://b/x\n", listOf("udp://b/x"))
        assertEquals(listOf("udp://a/x"), parsed.fresh)
        assertEquals(2, parsed.duplicates)
        assertEquals(0, parsed.invalid)
    }

    @Test
    fun `a line with a space inside is not a tracker link`() {
        assertEquals(1, parseTrackers("udp://a b/x", emptyList()).invalid)
    }

    // Loading

    @Test
    fun `it loads the trackers with the default ones first, then the custom ones`() =
        model(
            detail(tracker("udp://custom", custom = true, tier = 0), tracker("udp://one", tier = 1), tracker("udp://two", tier = 2)),
        ) { _, model ->
            val state = model.state.value
            assertTrue(state.load is TrackersLoad.Loaded)
            assertEquals(listOf("udp://one", "udp://two", "udp://custom"), state.rows.map { it.url })
            assertEquals(listOf(false, false, true), state.rows.map { it.custom })
            assertEquals("Trackers (3)", state.heading)
            assertTrue(state.canAdd.not(), "nothing typed yet")
        }

    @Test
    fun `a torrent that is not running says so and still takes new trackers`() =
        model(TorrentDetailResult.NotInSession) { _, model ->
            val state = model.state.value
            assertEquals(TrackersLoad.NotRunning, state.load)
            assertEquals("Not running. Trackers can be added now and apply when it starts.", state.loadMessage)
            model.setInput("udp://a/x")
            assertTrue(model.state.value.canAdd)
            assertFalse(model.state.value.canReannounce)
        }

    @Test
    fun `a stopped private torrent hides the add form and shows the private notice`() =
        model(TorrentDetailResult.PrivateNotInSession) { source, model ->
            val state = model.state.value
            assertEquals(TrackersLoad.NotRunningPrivate, state.load)
            assertTrue(state.private)
            assertEquals("Not running. Its trackers show here while it runs.", state.loadMessage)
            model.setInput("udp://a/x")
            assertFalse(model.state.value.canAdd)
            model.submit()
            assertEquals(emptyList(), source.calls.filter { it.startsWith("addTrackers") })
            assertFalse(model.state.value.canReannounce)
        }

    @Test
    fun `a failed load shows its error`() =
        runTest(UnconfinedTestDispatcher()) {
            val source = FakeSource()
            source.detailThrows = IllegalStateException("boom")
            val model = TrackersModel(source, id, backgroundScope)
            val state = model.state.value
            assertEquals(TrackersLoad.Failed("Couldn't load trackers: boom"), state.load)
            assertEquals("Couldn't load trackers: boom", state.loadMessage)
        }

    @Test
    fun `it stays loading until the engine answers`() =
        runTest(UnconfinedTestDispatcher()) {
            val source = FakeSource()
            source.gates["torrentDetail 1"] = kotlinx.coroutines.CompletableDeferred()
            source.torrent = detail()
            val model = TrackersModel(source, id, backgroundScope)
            assertEquals(TrackersLoad.Loading, model.state.value.load)
            assertEquals("Loading trackers…", model.state.value.loadMessage)
            model.setInput("udp://a/x")
            assertFalse(model.state.value.canAdd)
            source.gates.getValue("torrentDetail 1").complete(Unit)
            assertTrue(model.state.value.load is TrackersLoad.Loaded)
            assertTrue(model.state.value.canAdd)
        }

    @Test
    fun `no trackers say none are configured`() =
        model(detail()) { _, model ->
            assertEquals("No announce trackers configured.", model.state.value.loadMessage)
            assertEquals("Trackers (0)", model.state.value.heading)
        }

    @Test
    fun `a private torrent shows the notice and cannot be added to`() =
        model(detail(tracker("udp://one"), private = true)) { _, model ->
            val state = model.state.value
            assertTrue(state.private)
            assertEquals(PRIVATE_TRACKERS_NOTICE, "This is a private torrent. External trackers cannot be added to private swarms.")
            model.setInput("udp://a/x")
            assertFalse(model.state.value.canAdd)
        }

    // Adding

    @Test
    fun `valid lines are added, the field clears and the list reloads`() =
        model(detail(tracker("udp://one"))) { source, model ->
            model.setInput("udp://a/x\nhttp://b/y")
            source.torrent = detail(tracker("udp://one"), tracker("udp://a/x", custom = true), tracker("http://b/y", custom = true))
            model.submit()
            assertEquals(listOf("addTrackers 1 [udp://a/x, http://b/y]"), source.calls.filter { it.startsWith("addTrackers") })
            val state = model.state.value
            assertEquals("", state.input)
            assertNull(state.error)
            assertNull(state.ignored)
            assertFalse(state.submitting)
            assertEquals(3, state.rows.size)
        }

    @Test
    fun `some invalid lines are ignored with a count and the valid ones are added`() =
        model(detail()) { source, model ->
            model.setInput("udp://a/x\nnope\nalso nope")
            model.submit()
            assertEquals(listOf("addTrackers 1 [udp://a/x]"), source.calls.filter { it.startsWith("addTrackers") })
            assertEquals("", model.state.value.input)
            assertEquals("2 line(s) weren't tracker link(s) and were ignored.", model.state.value.ignored)
            assertNull(model.state.value.error)
        }

    @Test
    fun `only invalid lines show an error, add nothing and keep the field`() =
        model(detail()) { source, model ->
            model.setInput("nope\nftp://x")
            model.submit()
            assertEquals(emptyList(), source.calls.filter { it.startsWith("addTrackers") })
            assertEquals("2 line(s) weren't tracker link(s)", model.state.value.error)
            assertEquals("nope\nftp://x", model.state.value.input)
        }

    @Test
    fun `a blank field asks for a valid tracker`() =
        model(detail()) { source, model ->
            model.setInput("   ")
            model.submit()
            assertEquals(emptyList(), source.calls.filter { it.startsWith("addTrackers") })
            assertEquals("Please enter at least one valid tracker URL (e.g. udp://... or http://...)", model.state.value.error)
        }

    @Test
    fun `lines that are all listed already add nothing and clear the field`() =
        model(detail(tracker("udp://one"))) { source, model ->
            model.setInput("udp://one")
            model.submit()
            assertEquals(emptyList(), source.calls.filter { it.startsWith("addTrackers") })
            assertEquals("", model.state.value.input)
            assertNull(model.state.value.error)
        }

    @Test
    fun `a torrent that is not running keeps them for its next start`() =
        model(TorrentDetailResult.NotInSession) { source, model ->
            source.trackerAction = TorrentActionResult.NotInSession
            model.setInput("udp://a/x")
            model.submit()
            val state = model.state.value
            assertEquals("", state.input)
            assertNull(state.error)
            assertEquals("Saved. The trackers are added when the torrent starts.", state.info)
        }

    @Test
    fun `a private answer from the engine refuses and keeps the field`() =
        model(detail()) { source, model ->
            source.trackerAction = TorrentActionResult.Private
            model.setInput("udp://a/x")
            model.submit()
            assertTrue(model.state.value.private)
            assertEquals("udp://a/x", model.state.value.input)
        }

    @Test
    fun `an engine that is unavailable shows an error and keeps the field`() =
        model(detail()) { source, model ->
            source.trackerAction = TorrentActionResult.Unavailable
            model.setInput("udp://a/x")
            model.submit()
            assertEquals("Trackers can't be added right now.", model.state.value.error)
            assertEquals("udp://a/x", model.state.value.input)
            assertFalse(model.state.value.submitting)
        }

    @Test
    fun `a failing add shows the error and keeps the field`() =
        model(detail()) { source, model ->
            source.trackerActionThrows = IllegalStateException("gone")
            model.setInput("udp://a/x")
            model.submit()
            assertEquals("Couldn't add trackers: gone", model.state.value.error)
            assertEquals("udp://a/x", model.state.value.input)
            assertFalse(model.state.value.submitting)
        }

    @Test
    fun `lines that are listed already or not links add nothing, clear the field and show the ignored line`() =
        model(detail(tracker("udp://one"))) { source, model ->
            model.setInput("udp://one\nnope")
            model.submit()
            assertEquals(emptyList(), source.calls.filter { it.startsWith("addTrackers") })
            assertEquals("", model.state.value.input)
            assertNull(model.state.value.error)
            assertEquals("1 line(s) weren't tracker link(s) and were ignored.", model.state.value.ignored)
        }

    @Test
    fun `an add that was started still finishes when the dialog is closed`() =
        runTest(UnconfinedTestDispatcher()) {
            val source = FakeSource()
            source.torrent = detail()
            val job = Job()
            val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + job)
            val model = TrackersModel(source, id, scope)
            val gate = CompletableDeferred<Unit>()
            source.gates["addTrackers 1 [udp://a/x]"] = gate
            model.setInput("udp://a/x")
            model.submit()
            assertTrue(model.state.value.submitting)

            job.cancel()
            gate.complete(Unit)

            assertTrue("addTrackers 1 [udp://a/x]" in source.finished, "the engine call ran to its answer")
            assertFalse(model.state.value.submitting)
            assertEquals("", model.state.value.input)
        }

    @Test
    fun `a reload that fails after an add keeps the list and shows the error line`() =
        model(detail(tracker("udp://one"))) { source, model ->
            model.setInput("udp://a/x")
            source.detailThrows = IllegalStateException("boom")
            model.submit()
            val state = model.state.value
            assertEquals(listOf("udp://one"), state.rows.map { it.url })
            assertTrue(state.load is TrackersLoad.Loaded)
            assertEquals("Couldn't refresh the trackers: boom", state.error)
            assertEquals("", state.input)
            assertFalse(state.submitting)
        }

    @Test
    fun `adding is off while an add runs`() =
        model(detail()) { source, model ->
            val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
            source.gates["addTrackers 1 [udp://a/x]"] = gate
            model.setInput("udp://a/x")
            model.submit()
            assertTrue(model.state.value.submitting)
            assertFalse(model.state.value.canAdd)
            model.submit()
            assertEquals(1, source.calls.count { it.startsWith("addTrackers") })
            gate.complete(Unit)
            assertFalse(model.state.value.submitting)
        }

    // Reannounce

    @Test
    fun `reannounce asks the engine for all trackers`() =
        model(detail(tracker("udp://one"))) { source, model ->
            assertTrue(model.state.value.canReannounce)
            model.reannounce()
            assertEquals(listOf("reannounce 1"), source.calls.filter { it.startsWith("reannounce") })
            assertNull(model.state.value.error)
        }

    @Test
    fun `a reload that fails after a reannounce keeps the list and shows the error line`() =
        model(detail(tracker("udp://one"))) { source, model ->
            source.detailThrows = IllegalStateException("boom")
            model.reannounce()
            val state = model.state.value
            assertEquals(listOf("udp://one"), state.rows.map { it.url })
            assertTrue(state.load is TrackersLoad.Loaded)
            assertEquals("Couldn't refresh the trackers: boom", state.error)
        }

    @Test
    fun `a failing reannounce shows an error`() =
        model(detail(tracker("udp://one"))) { source, model ->
            source.trackerActionThrows = IllegalStateException("gone")
            model.reannounce()
            assertEquals("Couldn't reannounce: gone", model.state.value.error)
        }
}
