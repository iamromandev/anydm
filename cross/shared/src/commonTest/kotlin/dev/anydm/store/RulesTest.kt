package dev.anydm.store

import dev.anydm.model.EntryCounts
import dev.anydm.model.Position
import dev.anydm.model.FileProgressDto
import dev.anydm.model.LiveDto
import dev.anydm.model.ProgressDto
import dev.anydm.model.TaskKind
import dev.anydm.model.TaskFile
import dev.anydm.model.Task
import dev.anydm.model.TaskStatus.CANCELED
import dev.anydm.model.TaskStatus.COMPLETE
import dev.anydm.model.TaskStatus.DOWNLOADING
import dev.anydm.model.TaskStatus.PAUSED
import dev.anydm.model.TaskStatus.PENDING
import kotlin.test.Test
import kotlin.test.assertEquals

class RulesTest {
    private fun ids(rows: List<Task>) = rows.map { it.id }

    @Test
    fun `placeRows updates a held row where it is`() {
        val placed = placeRows(listOf(task("a"), task("b"), task("c")), listOf(task("b", COMPLETE)))
        assertEquals(listOf("a" to DOWNLOADING, "b" to COMPLETE, "c" to DOWNLOADING), placed.map { it.id to it.status })
    }

    @Test
    fun `placeRows puts only rows new to the list on top`() {
        assertEquals(listOf("n", "a", "b"), ids(placeRows(listOf(task("a"), task("b")), listOf(task("n"), task("b")))))
    }

    @Test
    fun `placeRows never lists a group's video`() {
        assertEquals(listOf("g"), ids(placeRows(listOf(task("g")), listOf(task("v", PENDING, parentId = "g")))))
    }

    @Test
    fun `placeRows drops a canceled row`() {
        assertEquals(listOf("b"), ids(placeRows(listOf(task("a"), task("b")), listOf(task("a", CANCELED)))))
    }

    @Test
    fun `settlePage keeps the stream's copy of a row it changed while the page was out`() {
        val settled = settlePage(listOf(task("a", DOWNLOADING)), listOf(task("a", COMPLETE)), setOf("a"))
        assertEquals(listOf(COMPLETE), settled.map { it.status })
    }

    @Test
    fun `settlePage takes the page's copy of every row the stream left alone`() {
        val settled =
            settlePage(
                listOf(task("a", progress = 60), task("b", COMPLETE)),
                listOf(task("a", progress = 20), task("b", DOWNLOADING)),
                emptySet(),
            )
        assertEquals(
            listOf(Triple("a", DOWNLOADING, 60), Triple("b", COMPLETE, 0)),
            settled.map { Triple(it.id, it.status, it.progress) },
        )
    }

    @Test
    fun `settlePage keeps every row a burst of frames wrote, new and removed alike`() {
        val settled =
            settlePage(
                listOf(task("g"), task("a"), task("gone"), task("b")),
                listOf(task("new"), task("g", PAUSED), task("a", COMPLETE)),
                setOf("new", "g", "a", "gone"),
            )
        assertEquals(
            listOf("new" to DOWNLOADING, "g" to PAUSED, "a" to COMPLETE, "b" to DOWNLOADING),
            settled.map { it.id to it.status },
        )
    }

    @Test
    fun `appendPage keeps held rows in place and adds the rest after`() {
        val appended = appendPage(listOf(task("a"), task("b")), listOf(task("b", COMPLETE), task("c")))
        assertEquals(listOf("a" to DOWNLOADING, "b" to COMPLETE, "c" to DOWNLOADING), appended.map { it.id to it.status })
    }

    @Test
    fun `applyProgress changes what the frame names and nothing else`() {
        val tasks = listOf(task("a", progress = 10, totalBytes = 100), task("b"))
        val after = applyProgress(tasks, ProgressDto(id = "a", progress = 40, live = LiveDto(speedBps = 7, peers = 3)))
        assertEquals(40, after[0].progress)
        assertEquals(100, after[0].totalBytes)
        assertEquals(7, after[0].downloadSpeed)
        assertEquals(3, after[0].peersConnected)
        assertEquals(tasks[1], after[1])
    }

    @Test
    fun `applyProgress takes a torrent's file bytes and keeps what the frame left out`() {
        val torrent =
            task("t", kind = TaskKind.TORRENT).copy(
                files = listOf(TaskFile(0, "a.mkv", 100, true, 10), TaskFile(1, "b.nfo", 5, false, 0)),
                etaSeconds = 30,
            )
        val after = applyProgress(listOf(torrent), ProgressDto(id = "t", files = listOf(FileProgressDto(0, 60)))).single()
        assertEquals(listOf(60L, 0L), after.files?.map { it.downloadedBytes })
        assertEquals(30, after.etaSeconds)
    }

    @Test
    fun `keepHeld keeps positions and a watched count a frame left out`() {
        val counts = EntryCounts(3, 1, 2, 1, 0, 0, null)
        val held =
            listOf(
                task("g", entryCounts = counts.copy(watched = 1), positions = listOf(Position(0, 10.0, 60.0, false))),
            )
        val kept = keepHeld(listOf(task("g", COMPLETE, entryCounts = counts.copy(complete = 3))), held)
        assertEquals(1, kept.single().entryCounts?.watched)
        assertEquals(3, kept.single().entryCounts?.complete)
        assertEquals(
            10.0,
            kept
                .single()
                .positions
                ?.single()
                ?.positionSeconds,
        )
    }
}
