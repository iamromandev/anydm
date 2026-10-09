package dev.anydm.store

import dev.anydm.api.ApiException
import dev.anydm.api.Duplicate
import dev.anydm.api.ErrorDetail
import dev.anydm.api.Page
import dev.anydm.api.PlaylistLink
import dev.anydm.api.ServerEvent
import dev.anydm.api.Unauthorized
import dev.anydm.model.PageMetaDto
import dev.anydm.model.PlaylistDto
import dev.anydm.model.ProgressDto
import dev.anydm.model.TaskDto
import dev.anydm.model.TaskKind
import dev.anydm.model.TaskStatus.CANCELLED
import dev.anydm.model.TaskStatus.COMPLETED
import dev.anydm.model.TaskStatus.DOWNLOADING
import dev.anydm.model.TaskStatus.PAUSED
import dev.anydm.model.TaskStatus.PENDING
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class TaskStoreTest {
    private val api = FakeApi()
    private val seen = mutableListOf<StoreEvent>()

    private fun TestScope.store(): TaskStore {
        api.clock = { testScheduler.currentTime }
        val store = TaskStore(api, backgroundScope, now = { testScheduler.currentTime })
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { store.events.toList(seen) }
        return store
    }

    private fun page(
        vararg rows: TaskDto,
        totalPages: Int = 1,
        number: Int = 1,
    ) = Page(rows.toList(), PageMetaDto(page = number, totalPages = totalPages))

    private fun said(message: String) = seen.filterIsInstance<StoreEvent.Said>().any { it.notice.message == message }

    @Test
    fun `starting loads the first page and the counts, and goes live on the first frame`() =
        runTest {
            api.pages[1] = page(dto("a"), totalPages = 2)
            api.stream = {
                emit(ServerEvent.Snapshot(listOf(task("b"))))
                awaitCancellation()
            }
            val store = store()

            store.start()
            runCurrent()

            val state = store.state.value
            assertEquals(Connection.Live, state.connection)
            assertEquals(listOf("b", "a"), state.tasks.map { it.id })
            assertEquals(2, state.totalPages)
            assertEquals(1, state.summary?.all)
        }

    @Test
    fun `frames update rows in place, keep videos out, and drop what was canceled`() =
        runTest {
            api.stream = {
                emit(ServerEvent.Snapshot(listOf(task("a"), task("b"))))
                emit(ServerEvent.TaskChanged(task("b", COMPLETED)))
                emit(ServerEvent.TaskChanged(task("v", parentId = "g")))
                emit(ServerEvent.TaskChanged(task("a", CANCELLED)))
                awaitCancellation()
            }
            val store = store()

            store.start()
            runCurrent()

            assertEquals(
                listOf("b" to COMPLETED),
                store.state.value.tasks
                    .map { it.id to it.status },
            )
            assertTrue(said("Finished: b"))
        }

    @Test
    fun `a pause or remove asks for the counts again, and a claim does not`() =
        runTest {
            val frames = Channel<ServerEvent>(Channel.UNLIMITED)
            api.stream = { for (frame in frames) emit(frame) }
            val store = store()
            store.start()
            frames.send(ServerEvent.Snapshot(listOf(task("a", PENDING), task("b"))))
            runCurrent()
            val asked = api.summaryCalls

            frames.send(ServerEvent.TaskChanged(task("a", DOWNLOADING)))
            runCurrent()
            assertEquals(asked, api.summaryCalls)

            frames.send(ServerEvent.TaskChanged(task("a", PAUSED)))
            runCurrent()
            assertEquals(asked + 1, api.summaryCalls)

            frames.send(ServerEvent.TaskChanged(task("b", CANCELLED)))
            runCurrent()
            assertEquals(asked + 2, api.summaryCalls)
        }

    @Test
    fun `a progress frame changes only what it names`() =
        runTest {
            api.stream = {
                emit(ServerEvent.Snapshot(listOf(task("a", progress = 10, totalBytes = 100))))
                emit(ServerEvent.Progress(ProgressDto(id = "a", progress = 40)))
                emit(ServerEvent.Progress(ProgressDto(id = "video", collectionId = "a", progress = 99)))
                awaitCancellation()
            }
            val store = store()

            store.start()
            runCurrent()

            val row =
                store.state.value.tasks
                    .single()
            assertEquals(40, row.progress)
            assertEquals(100, row.totalBytes)
        }

    @Test
    fun `a dropped stream reconnects after 1, 2, 5, then every 10 seconds`() =
        runTest {
            val store = store()

            store.start()
            runCurrent()
            advanceTimeBy(28_001)

            assertEquals(listOf(0L, 1_000L, 3_000L, 8_000L, 18_000L, 28_000L), api.connects)
            assertTrue(store.state.value.connection is Connection.Degraded)
        }

    @Test
    fun `while degraded it polls the list, warns once, and says when it's back`() =
        runTest {
            val store = store()
            store.start()
            runCurrent()
            assertEquals(1, api.listCalls.size)

            advanceTimeBy(20_001)

            assertEquals(3, api.listCalls.size)
            assertEquals(1, seen.count { it == StoreEvent.Said(Notice(Tone.ERROR, LOST_CONTACT)) })

            api.stream = {
                emit(ServerEvent.Snapshot(emptyList()))
                awaitCancellation()
            }
            store.retryNow()
            runCurrent()

            assertEquals(Connection.Live, store.state.value.connection)
            assertTrue(said(BACK_IN_CONTACT))
        }

    @Test
    fun `a refused key signs out and stops retrying`() =
        runTest {
            api.stream = { throw Unauthorized("bad key") }
            val store = store()

            store.start()
            runCurrent()
            advanceTimeBy(60_000)

            assertEquals(Connection.Offline, store.state.value.connection)
            assertEquals(1, api.connects.size)
            assertTrue(StoreEvent.SignedOut("bad key") in seen)
        }

    @Test
    fun `a page that lands after a frame keeps the frame's copy`() =
        runTest {
            api.pages[1] = page(dto("a", DOWNLOADING))
            val gate = CompletableDeferred<Unit>()
            api.pageGate = gate
            api.stream = {
                emit(ServerEvent.TaskChanged(task("a", COMPLETED)))
                awaitCancellation()
            }
            val store = store()

            store.start()
            runCurrent()
            gate.complete(Unit)
            runCurrent()

            assertEquals(
                listOf(COMPLETED),
                store.state.value.tasks
                    .map { it.status },
            )
        }

    @Test
    fun `actions replace their row, and a failure is said rather than thrown`() =
        runTest {
            api.pages[1] = page(dto("a", PAUSED))
            api.stream = { awaitCancellation() }
            val store = store()
            store.start()
            runCurrent()

            api.answer = dto("a", DOWNLOADING)
            assertTrue(store.resume("a"))
            assertEquals(
                DOWNLOADING,
                store.state.value.tasks
                    .single()
                    .status,
            )

            api.failWith = ApiException("Task is not paused", 409, null)
            assertFalse(store.pause("a"))
            assertTrue(said("Task is not paused"))

            store.remove("a", deleteFiles = false)
            assertEquals(emptyList(), store.state.value.tasks)
            assertEquals(listOf("a"), api.removed)
            assertEquals(emptyList(), api.viaCollection)
        }

    @Test
    fun `a playlist row is paused, resumed and removed as a collection`() =
        runTest {
            api.pages[1] = page(TaskDto(type = "collection", id = "g", kind = "playlist", status = "downloading"))
            api.stream = { awaitCancellation() }
            val store = store()
            store.start()
            runCurrent()

            api.answer = TaskDto(type = "collection", id = "g", kind = "playlist", status = "paused")
            assertTrue(store.pause("g"))
            assertTrue(store.resume("g"))
            store.remove("g", deleteFiles = true)
            assertEquals(listOf("g", "g", "g"), api.viaCollection)
        }

    @Test
    fun `a playlist link says to use the web app`() =
        runTest {
            api.stream = { awaitCancellation() }
            api.failWith = PlaylistLink(PlaylistDto())
            val store = store()
            store.start()
            runCurrent()

            assertFalse(store.add("https://youtube.com/playlist?list=PL1", preferredPreset = "best"))
            assertTrue(said("Playlists are added from the web app for now"))
        }

    private val refused =
        ApiException("Already in your list: Clip (completed)", 409, null, listOf(ErrorDetail("d1", "Clip", listOf("completed"))))

    private fun duplicates() = seen.filterIsInstance<StoreEvent.Duplicated>()

    @Test
    fun `a link the list holds is a duplicate to offer on, not an error`() =
        runTest {
            api.stream = { awaitCancellation() }
            api.failWith = refused
            val store = store()
            store.start()
            runCurrent()

            assertFalse(store.add("https://x/a.iso", preferredPreset = "best"))
            assertEquals(listOf(StoreEvent.Duplicated(Duplicate("d1", "Clip", "completed"), "https://x/a.iso")), duplicates())
            assertTrue(seen.filterIsInstance<StoreEvent.Said>().isEmpty())
        }

    @Test
    fun `Add anyway sends the link again as a second copy`() =
        runTest {
            api.stream = { awaitCancellation() }
            api.failWith = refused
            api.answer = dto("copy")
            val store = store()
            store.start()
            runCurrent()

            assertFalse(store.add("https://x/a.iso", preferredPreset = "best"))
            assertTrue(store.add("https://x/a.iso", preferredPreset = "best", allowDuplicate = true))
            assertEquals(listOf(false, true), api.allowed)
            assertTrue(
                "copy" in
                    store.state.value.tasks
                        .map { it.id },
            )
        }

    @Test
    fun `a torrent the list holds is a duplicate with no second copy to offer`() =
        runTest {
            api.stream = { awaitCancellation() }
            api.failWith = refused
            val store = store()
            store.start()
            runCurrent()

            assertFalse(store.addTorrent("magnet:?xt=urn:btih:held"))
            assertEquals(listOf(StoreEvent.Duplicated(Duplicate("d1", "Clip", "completed"), null)), duplicates())
        }

    @Test
    fun `a 409 that names no download is still an error`() =
        runTest {
            api.stream = { awaitCancellation() }
            api.failWith = ApiException("Task is paused", 409, null)
            val store = store()
            store.start()
            runCurrent()

            assertFalse(store.add("https://x/a.iso", preferredPreset = "best"))
            assertTrue(duplicates().isEmpty())
            assertTrue(said("Task is paused"))
        }

    @Test
    fun `revealing a row on the first page shows every download and finds it there`() =
        runTest {
            api.pages[1] = page(dto("a"), dto("b"))
            api.stream = { awaitCancellation() }
            val store = store()
            store.start()
            runCurrent()
            store.setFilter(ListFilter.COMPLETED)
            runCurrent()
            api.listCalls.clear()

            assertTrue(store.reveal("b"))
            assertEquals(ListFilter.ALL, store.state.value.filter)
            assertEquals(listOf(1 to "all"), api.listCalls)
            assertTrue(api.taskCalls.isEmpty())
        }

    @Test
    fun `revealing a row past the first page fetches it on its own, and says so when it is gone`() =
        runTest {
            api.pages[1] = page(dto("a"), totalPages = 3)
            api.rows["far"] = dto("far")
            api.stream = { awaitCancellation() }
            val store = store()
            store.start()
            runCurrent()

            assertTrue(store.reveal("far"))
            assertEquals(listOf("far"), api.taskCalls)
            assertTrue(
                "far" in
                    store.state.value.tasks
                        .map { it.id },
            )
            assertFalse(store.reveal("gone"))
        }

    @Test
    fun `a sweep says what it did, or that there was nothing to do`() =
        runTest {
            api.stream = { awaitCancellation() }
            val store = store()
            store.start()
            runCurrent()

            api.affected = 3
            store.bulk(BulkAction.PAUSE_ALL)
            assertTrue(said("Paused 3 downloads"))
            api.affected = 1
            store.bulk(BulkAction.RESUME_ALL)
            assertTrue(said("Resumed 1 download"))
            api.affected = 0
            store.bulk(BulkAction.CLEAR_FINISHED)
            assertTrue(said("Nothing to do"))
        }

    @Test
    fun `a filter asks the API for that view, and more pages add on`() =
        runTest {
            api.pages[1] = page(dto("a"), totalPages = 2)
            api.pages[2] = page(dto("b"), totalPages = 2, number = 2)
            api.stream = { awaitCancellation() }
            val store = store()
            store.start()
            runCurrent()

            store.loadMore()
            runCurrent()
            assertEquals(
                listOf("a", "b"),
                store.state.value.tasks
                    .map { it.id },
            )

            store.setFilter(ListFilter.COMPLETED)
            runCurrent()
            assertEquals(1 to "completed", api.listCalls.last())
            assertEquals(ListFilter.COMPLETED, store.state.value.filter)
        }

    @Test
    fun `an open group's videos load, follow their frames, and go when it closes`() =
        runTest {
            api.entries["g"] = listOf(dto("v2").copy(collectionId = "g", position = 2), dto("v1").copy(collectionId = "g", position = 1))
            api.stream = {
                emit(ServerEvent.Snapshot(listOf(task("g", kind = TaskKind.PLAYLIST))))
                awaitCancellation()
            }
            val store = store()
            store.start()
            runCurrent()

            store.expand("g")
            runCurrent()
            assertEquals(
                listOf("v1", "v2"),
                store.state.value.entries["g"]
                    ?.map { it.id },
            )

            api.stream = {}
            store.onFrame(ServerEvent.TaskChanged(task("v1", COMPLETED, parentId = "g")))
            store.onFrame(ServerEvent.Progress(ProgressDto(id = "v2", collectionId = "g", progress = 55)))
            store.onFrame(ServerEvent.TaskChanged(task("other", parentId = "closed")))
            runCurrent()
            val open =
                store.state.value.entries
                    .getValue("g")
            assertEquals(COMPLETED, open.first { it.id == "v1" }.status)
            assertEquals(55, open.first { it.id == "v2" }.progress)
            assertEquals(setOf("g"), store.state.value.entries.keys)
            assertEquals(
                listOf("g"),
                store.state.value.tasks
                    .map { it.id },
            )

            store.collapse("g")
            assertEquals(emptyMap(), store.state.value.entries)
        }
}
