package dev.anydm.desktop

import dev.anydm.engine.http.JobSpec
import dev.anydm.engine.http.Span
import dev.anydm.engine.http.TRAILER_BYTES
import dev.anydm.engine.torrent.MagnetResult
import dev.anydm.engine.torrent.PieceSet
import dev.anydm.engine.torrent.TorrentJob
import dev.anydm.engine.torrent.parseMagnet
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EngineBridgeTest {
    private fun row(
        id: String,
        status: DownloadStatus,
        url: String? = "https://x.test/$id.zip",
        kind: DownloadKind = DownloadKind.Direct,
        folder: String? = null,
    ) = DownloadItem(id, "f$id.zip", 100, 40, 0, status, kind, folder, null, url, null, "\"e\"", "Mon")

    private fun state(vararg rows: DownloadItem) =
        QueueState(rows.toList(), rows.filter { it.status == DownloadStatus.Queued }.map { it.id })

    private fun cmds(
        prev: QueueState,
        next: QueueState,
    ) = engineCommands(prev, next, "/home/u/Downloads", NoTorrentFiles, ByteArray(20))

    @Test
    fun aRowThatBecomesDownloadingStartsWithItsOwnDataAndTheDefaultFolder() {
        val out = cmds(state(row("1", DownloadStatus.Paused)), state(row("1", DownloadStatus.Downloading)))
        assertEquals(
            listOf(EngineCmd.Start(JobSpec("1", "https://x.test/1.zip", "/home/u/Downloads", "f1.zip", 40, "\"e\"", "Mon", 100))),
            out,
        )
    }

    @Test
    fun theRowsOwnFolderWins() {
        val out = cmds(state(), state(row("1", DownloadStatus.Downloading, folder = "/data")))
        assertEquals("/data", (out.single() as EngineCmd.Start).job.folder)
    }

    @Test
    fun aRowThatStopsDownloadingStops() {
        for (to in listOf(DownloadStatus.Paused, DownloadStatus.Queued, DownloadStatus.Finished, DownloadStatus.Failed)) {
            assertEquals(
                listOf(EngineCmd.Stop("1")),
                cmds(state(row("1", DownloadStatus.Downloading)), state(row("1", to))),
                "$to",
            )
        }
    }

    @Test
    fun aRemovedDownloadingRowStops() {
        assertEquals(listOf(EngineCmd.Stop("1")), cmds(state(row("1", DownloadStatus.Downloading)), state()))
    }

    @Test
    fun aNewDownloadingRowStarts() {
        assertEquals(1, cmds(state(), state(row("1", DownloadStatus.Downloading))).size)
    }

    @Test
    fun aQueuedRowStartsWhenItsSlotOpensAndTheFinishedOneStops() {
        val prev = state(row("1", DownloadStatus.Downloading), row("2", DownloadStatus.Queued))
        val next = state(row("1", DownloadStatus.Finished), row("2", DownloadStatus.Downloading))
        assertEquals(
            listOf("Stop:1", "Start:2"),
            cmds(prev, next).map {
                if (it is EngineCmd.Stop) "Stop:${it.id}" else "Start:${(it as EngineCmd.Start).job.id}"
            },
        )
    }

    @Test
    fun progressOnlyChangesProduceNothing() {
        val prev = state(row("1", DownloadStatus.Downloading))
        assertEquals(emptyList(), cmds(prev, progressed(prev, "1", 50, 100, 9)))
    }

    @Test
    fun torrentRowsAndRowsWithoutAUrlNeverProduceCommands() {
        val torrent = row("1", DownloadStatus.Downloading, url = null, kind = DownloadKind.Torrent)
        val sample = row("2", DownloadStatus.Downloading, url = null)
        assertEquals(emptyList(), cmds(state(), state(torrent, sample)))
        assertEquals(emptyList(), cmds(state(torrent, sample), state()))
        assertEquals(emptyList(), cmds(state(torrent), state(torrent.copy(status = DownloadStatus.Paused))))
    }

    @Test
    fun anUnknownSizeGivesTheJobNoTotal() {
        val out = cmds(state(), state(row("1", DownloadStatus.Downloading).copy(totalBytes = UNKNOWN_SIZE)))
        assertEquals(null, (out.single() as EngineCmd.Start).job.total)
    }

    private val job = JobSpec("1", "https://x.test/1.zip", "/d", "a.zip", 0, null, null)

    private fun env(
        engine: FakeEngine = FakeEngine(),
        opener: FakeOpener = FakeOpener(),
        disk: MemoryDiskIo = MemoryDiskIo(),
    ) = DownloadEnv(engine, FakeProber(), opener, disk)

    @Test
    fun aStartTakesItsDoneFromThePartFileAndNeverRenamesTheJob() {
        val disk = MemoryDiskIo()
        disk.put("/d/a.zip", 5) // somebody else's file of that name: the row's name is already settled, it stays
        disk.put("/d/a.zip.part", 30)
        val engine = FakeEngine()
        runBlocking { runEngineCommand(env(engine, disk = disk), EngineCmd.Start(job.copy(done = 10))) }
        assertEquals(listOf(job.copy(done = 30)), engine.starts.toList())
    }

    @Test
    fun aStartWithNoPartFileStartsFromZero() {
        val engine = FakeEngine()
        runBlocking { runEngineCommand(env(engine), EngineCmd.Start(job.copy(done = 10))) }
        assertEquals(0L, engine.starts.single().done)
    }

    @Test
    fun aCommandThatThrowsDoesNotStopTheNextOnes() {
        val engine = FakeEngine().apply { failStarts = 1 }
        val e = env(engine)
        runBlocking {
            runEngineCommand(e, EngineCmd.Start(job))
            runEngineCommand(e, EngineCmd.Start(job.copy(id = "2")))
        }
        assertEquals(listOf("2"), engine.starts.map { it.id })
    }

    @Test
    fun aNameThatIsNotAPathStillReachesTheEngineWhichFailsItWithAReason() {
        val engine = FakeEngine()
        runBlocking { runEngineCommand(env(engine), EngineCmd.Start(job.copy(folder = "bad\u0000folder"))) }
        assertEquals(1, engine.starts.size)
    }

    @Test
    fun discardWaitsForTheJobsThenMovesEachFileToTheTrash() {
        val engine = FakeEngine()
        val opener = FakeOpener()
        val disk = MemoryDiskIo()
        val a = Path.of("/d/a.zip.part")
        runBlocking { runEngineCommand(env(engine, opener, disk), EngineCmd.Discard(setOf("1", "2"), listOf(a))) }
        assertEquals(listOf("join:1", "join:2", "forget:1", "forget:2"), engine.log.toList())
        assertEquals(listOf(a), opener.trashed.toList())
        assertEquals(emptyList(), disk.deleted.toList())
    }

    @Test
    fun discardDeletesForGoodWhenTheOsHasNoTrash() {
        val opener = FakeOpener(canTrash = false)
        val disk = MemoryDiskIo()
        val a = Path.of("/d/a.zip.part")
        disk.put(a.toString(), 3)
        runBlocking { runEngineCommand(env(opener = opener, disk = disk), EngineCmd.Discard(emptySet(), listOf(a))) }
        assertEquals(listOf(a), disk.deleted.toList())
        assertTrue(disk.files.isEmpty())
    }

    @Test
    fun discardDoesNotDeleteBeforeTheJobHasEnded() {
        val engine = FakeEngine().apply { joinGate = CompletableDeferred() }
        val opener = FakeOpener()
        val a = Path.of("/d/a.zip.part")
        runBlocking {
            val call = launch(Dispatchers.Default) { runEngineCommand(env(engine, opener), EngineCmd.Discard(setOf("1"), listOf(a))) }
            delay(200)
            assertEquals(emptyList(), opener.trashed.toList(), "deleted while the job was still running")
            engine.joinGate!!.complete(Unit)
            call.join()
            assertEquals(listOf(a), opener.trashed.toList())
        }
    }

    @Test
    fun aJobThatDoesNotEndInTimeKeepsItsFilesAndDoesNotBlockTheNextCommand() {
        val engine = FakeEngine().apply { joinGate = CompletableDeferred() } // never completes
        val opener = FakeOpener()
        val disk = MemoryDiskIo()
        val a = Path.of("/d/a.zip.part")
        disk.put(a.toString(), 3)
        val e = env(engine, opener, disk)
        runBlocking {
            runEngineCommand(e, EngineCmd.Discard(setOf("1"), listOf(a)), joinWaitMs = 100)
            runEngineCommand(e, EngineCmd.Start(job))
        }
        assertEquals(emptyList(), opener.trashed.toList(), "a file still being written must stay")
        assertEquals(emptyList(), disk.deleted.toList())
        assertEquals(listOf("1"), engine.starts.map { it.id })
    }

    // m6: what the engine kept of a removed row goes even when its job was slow to end (a start under a reused id clears it too)
    @Test
    fun aRowWhoseJobDoesNotEndInTimeIsStillForgottenButItsFilesStay() {
        val engine = FakeEngine().apply { joinGate = CompletableDeferred() } // never completes
        val opener = FakeOpener()
        val a = Path.of("/d/a.zip.part")
        runBlocking { runEngineCommand(env(engine, opener), EngineCmd.Discard(setOf("1"), listOf(a)), joinWaitMs = 100) }
        assertEquals(listOf("join:1", "forget:1"), engine.log.toList())
        assertEquals(emptyList(), opener.trashed.toList())
    }

    @Test
    fun aTorrentRowEvenWithAUrlNeverStarts() {
        assertTrue(cmds(state(), state(row("1", DownloadStatus.Downloading, kind = DownloadKind.Torrent))).isEmpty())
    }

    private val spans = listOf(Span(0, 49, 20), Span(50, 99, 5))

    @Test
    fun aSegmentedRowStartsWithItsConnectionsAndSpans() {
        val seg = row("1", DownloadStatus.Downloading).copy(connections = 4, spans = spans, partId = "AA")
        val out = cmds(state(), state(seg))
        val sent = (out.single() as EngineCmd.Start).job
        assertEquals(4, sent.connections)
        assertEquals(spans, sent.spans)
        assertEquals("AA", sent.partId)
    }

    @Test
    fun aStartWithSpansAndAPartFileKeepsTheSpansAndTakesDoneFromThem() {
        val disk = MemoryDiskIo()
        disk.put("/d/a.zip.part", 100 + TRAILER_BYTES) // full size from the start, and the identity after it: the size says nothing
        val engine = FakeEngine()
        runBlocking {
            runEngineCommand(env(engine, disk = disk), EngineCmd.Start(job.copy(done = 3, connections = 4, spans = spans, partId = "AA")))
        }
        val sent = engine.starts.single()
        assertEquals(spans, sent.spans)
        assertEquals("AA", sent.partId)
        assertEquals(25L, sent.done)
        assertEquals(4, sent.connections)
        assertTrue(disk.deleted.isEmpty(), "the part file holds the ranges' bytes")
    }

    @Test
    fun aStartWithSpansButNoPartFileStartsFromZeroWithoutSpans() {
        val engine = FakeEngine()
        runBlocking { runEngineCommand(env(engine), EngineCmd.Start(job.copy(done = 25, connections = 4, spans = spans, partId = "AA"))) }
        val sent = engine.starts.single()
        assertEquals(null, sent.spans)
        assertEquals(null, sent.partId)
        assertEquals(0L, sent.done)
        assertEquals(4, sent.connections)
    }

    @Test
    fun aRowOnSeveralConnectionsWithoutRangesLeavesItsPartFileToTheEngine() {
        // Whether that file is one stream's partial or a segmented run's with gaps is the engine's to tell, after the row's
        // predecessor has ended: at this point a pause just before has maybe not yet created it.
        val disk = MemoryDiskIo()
        disk.put("/d/a.zip.part", 30)
        val engine = FakeEngine()
        runBlocking { runEngineCommand(env(engine, disk = disk), EngineCmd.Start(job.copy(done = 100, total = 100, connections = 4))) }
        val sent = engine.starts.single()
        assertEquals(30L, sent.done)
        assertEquals(null, sent.spans)
        assertEquals(4, sent.connections)
        assertTrue(disk.deleted.isEmpty(), "the engine decides what happens to the file")
    }

    @Test
    fun aRowOnOneConnectionKeepsToday() {
        val disk = MemoryDiskIo()
        disk.put("/d/a.zip.part", 30)
        val engine = FakeEngine()
        runBlocking { runEngineCommand(env(engine, disk = disk), EngineCmd.Start(job.copy(connections = 1))) }
        assertEquals(30L, engine.starts.single().done)
        assertTrue(disk.deleted.isEmpty())
    }

    @Test
    fun discardTellsTheEngineToForgetTheRowsAfterTheJobsHaveEnded() {
        val engine = FakeEngine()
        runBlocking { runEngineCommand(env(engine), EngineCmd.Discard(setOf("1"), listOf(Path.of("/d/a.zip.part")))) }
        assertEquals(listOf("join:1", "forget:1"), engine.log.toList())
    }

    // Real torrents: rows with an info hash go to the torrent engine (step 14)
    private val hex = "cd".repeat(20)
    private val peerId = ByteArray(20) { it.toByte() }
    private val copies = FakeTorrentFiles()

    private fun tor(
        id: String,
        status: DownloadStatus,
        pieces: PieceSet? = null,
        infoHash: String? = hex,
    ) = DownloadItem(
        id,
        "T$id",
        100,
        40,
        0,
        status,
        DownloadKind.Torrent,
        folder = "/data",
        infoHash = infoHash,
        pieces = pieces,
    )

    private fun torCmds(
        prev: QueueState,
        next: QueueState,
    ) = engineCommands(prev, next, "/home/u/Downloads", copies, peerId)

    @Test
    fun aTorrentRowWithAnInfoHashStartsAJobFromItsCopyItsFolderAndItsPieces() {
        val set = PieceSet.of(4, listOf(1))
        val out = torCmds(state(), state(tor("1", DownloadStatus.Downloading, set)))
        val job = (out.single() as EngineCmd.StartTorrent).job
        assertEquals(
            listOf("1", "/app/torrents/$hex.torrent", "/data", "T1", set, 40L),
            listOf(job.id, job.torrentPath, job.folder, job.name, job.pieces, job.doneBytes),
        )
        assertTrue(peerId.contentEquals(job.peerId))
        assertEquals(emptyList(), job.peers)
    }

    @Test
    fun aTorrentRowThatNeverRanStartsWithNoPiecesSoTheEnginePicksAFreeName() {
        val job = (torCmds(state(), state(tor("1", DownloadStatus.Downloading))).single() as EngineCmd.StartTorrent).job
        assertEquals(null, job.pieces)
    }

    @Test
    fun aTorrentRowWithoutAFolderStartsInTheDefaultFolder() {
        val row = tor("1", DownloadStatus.Downloading).copy(folder = null)
        assertEquals("/home/u/Downloads", (torCmds(state(), state(row)).single() as EngineCmd.StartTorrent).job.folder)
    }

    @Test
    fun aTorrentRowThatStopsDownloadingStopsItsJob() {
        for (to in listOf(DownloadStatus.Paused, DownloadStatus.Queued, DownloadStatus.Finished, DownloadStatus.Failed)) {
            assertEquals(
                listOf(EngineCmd.StopTorrent("1")),
                torCmds(state(tor("1", DownloadStatus.Downloading)), state(tor("1", to))),
                "$to",
            )
        }
        assertEquals(listOf(EngineCmd.StopTorrent("1")), torCmds(state(tor("1", DownloadStatus.Downloading)), state()))
    }

    @Test
    fun aTorrentRowWithoutAnInfoHashStaysSimulatedAndNeverReachesAnEngine() {
        val old = tor("1", DownloadStatus.Downloading, infoHash = null)
        assertEquals(emptyList(), torCmds(state(), state(old)))
        assertEquals(emptyList(), torCmds(state(old), state()))
    }

    private val magnetHex = "08ada5a7a6183aae1e09d831df6748d566095a10"
    private val magnetUrl = "magnet:?xt=urn:btih:$magnetHex&dn=Sintel&tr=udp%3A%2F%2Ftracker.example%3A1337"

    private fun magnetRow(
        url: String? = magnetUrl,
        pieces: PieceSet? = null,
    ) = tor(
        "1",
        DownloadStatus.Downloading,
        pieces,
        infoHash = magnetHex,
    ).copy(url = url, name = "Sintel", files = null, totalBytes = UNKNOWN_SIZE, doneBytes = 0)

    @Test
    fun aMagnetRowStartsATorrentJobCarryingItsMagnetAndTheCopyPathItsMetadataWillBeSavedAt() {
        val job = (torCmds(state(), state(magnetRow())).single() as EngineCmd.StartTorrent).job
        val link = (parseMagnet(magnetUrl) as MagnetResult.Ok).link
        assertEquals(link, job.magnet)
        assertEquals(listOf("1", "/app/torrents/$magnetHex.torrent", "Sintel"), listOf(job.id, job.torrentPath, job.name))
        assertEquals(null, job.pieces, "a magnet row that never ran asks for a free name")
        assertEquals(0L, job.doneBytes)
    }

    @Test
    fun aMagnetRowThatRanKeepsTheNameAndPiecesItHasSoTheEngineKeepsItsFiles() {
        val set = PieceSet.of(4, listOf(1))
        val job = (torCmds(state(), state(magnetRow(pieces = set).copy(name = "Real name"))).single() as EngineCmd.StartTorrent).job
        assertEquals(listOf<Any?>("Real name", set), listOf(job.name, job.pieces))
    }

    @Test
    fun aTorrentRowWithNoMagnetLinkOrAnUnreadableOneStartsAJobWithNoMagnet() {
        assertEquals(null, (torCmds(state(), state(tor("1", DownloadStatus.Downloading))).single() as EngineCmd.StartTorrent).job.magnet)
        val bad = magnetRow(url = "magnet:?xt=urn:btih:abc")
        assertEquals(null, (torCmds(state(), state(bad)).single() as EngineCmd.StartTorrent).job.magnet)
    }

    @Test
    fun aMagnetRowThatStopsDownloadingStopsItsJobLikeAnyTorrent() {
        assertEquals(
            listOf(EngineCmd.StopTorrent("1")),
            torCmds(state(magnetRow()), state(magnetRow().copy(status = DownloadStatus.Paused))),
        )
    }

    @Test
    fun aHttpRowAndATorrentRowStartTogetherEachOnItsOwnEngine() {
        val out = torCmds(state(), state(row("1", DownloadStatus.Downloading), tor("2", DownloadStatus.Downloading)))
        assertEquals(listOf(true, true), listOf(out[0] is EngineCmd.Start, out[1] is EngineCmd.StartTorrent))
    }

    private fun torEnv(
        torrents: FakeTorrentEngine = FakeTorrentEngine(),
        engine: FakeEngine = FakeEngine(),
        opener: FakeOpener = FakeOpener(),
        disk: MemoryDiskIo = MemoryDiskIo(),
    ) = DownloadEnv(engine, FakeProber(), opener, disk, torrents = torrents, torrentFiles = copies)

    private val torJob = TorrentJob("1", "/app/torrents/$hex.torrent", "/data", "T1", null, peerId)

    @Test
    fun aTorrentStartAndStopGoToTheTorrentEngineOnly() {
        val torrents = FakeTorrentEngine()
        val engine = FakeEngine()
        runBlocking {
            runEngineCommand(torEnv(torrents, engine), EngineCmd.StartTorrent(torJob))
            runEngineCommand(torEnv(torrents, engine), EngineCmd.StopTorrent("1"))
        }
        assertEquals(listOf("start:1", "stop:1"), torrents.log.toList())
        assertEquals(emptyList(), engine.log.toList())
    }

    @Test
    fun aTorrentStartThatThrowsDoesNotStopTheNextCommand() {
        val engine = FakeEngine()
        val e = DownloadEnv(engine, FakeProber(), FakeOpener(), MemoryDiskIo(), torrents = TorrentEngineThatThrows())
        runBlocking {
            runEngineCommand(e, EngineCmd.StartTorrent(torJob))
            runEngineCommand(e, EngineCmd.Start(job))
        }
        assertEquals(1, engine.starts.size)
    }

    @Test
    fun discardJoinsAndForgetsOnBothEnginesThenTrashesTheFilesAndDeletesTheCopy() {
        val torrents = FakeTorrentEngine()
        val engine = FakeEngine()
        val opener = FakeOpener()
        val folder = Path.of("/data/T1")
        val part = Path.of("/data/T1.part")
        runBlocking {
            runEngineCommand(torEnv(torrents, engine, opener), EngineCmd.Discard(setOf("1"), listOf(folder, part), listOf(hex)))
        }
        assertEquals(listOf("join:1", "forget:1"), torrents.log.toList())
        assertEquals(listOf("join:1", "forget:1"), engine.log.toList())
        assertEquals(listOf(folder, part), opener.trashed.toList())
        assertEquals(listOf(hex), copies.deleted.toList())
    }

    @Test
    fun discardWithoutTheOsTrashDeletesAFolderWithEverythingInIt() {
        val disk = RecordingTreeDisk()
        val opener = FakeOpener(canTrash = false)
        val folder = Path.of("/data/T1")
        runBlocking { runEngineCommand(torEnv(opener = opener, disk = disk), EngineCmd.Discard(setOf("1"), listOf(folder))) }
        assertEquals(listOf(folder), disk.trees.toList())
    }

    @Test
    fun aCopyThatALiveRowHasAgainIsNotDeletedButTheOthersAre() {
        val other = "ef".repeat(20)
        runBlocking {
            runEngineCommand(
                torEnv(),
                EngineCmd.Discard(setOf("1"), emptyList(), listOf(hex, other)),
                liveHashes = { setOf(hex) },
            )
        }
        assertEquals(listOf(other), copies.deleted.toList())
    }

    @Test
    fun theLiveRowsAreAskedAtTheMomentTheCopyWouldGoNotBefore() {
        val torrents = FakeTorrentEngine().apply { joinGate = CompletableDeferred() }
        val live =
            java.util.concurrent.atomic
                .AtomicReference<Set<String>>(emptySet())
        runBlocking {
            val call =
                launch(Dispatchers.Default) {
                    runEngineCommand(
                        torEnv(torrents),
                        EngineCmd.Discard(setOf("1"), emptyList(), listOf(hex)),
                        liveHashes = { live.get() },
                    )
                }
            while ("join:1" !in torrents.log) delay(5)
            live.set(setOf(hex)) // the same torrent was added while the discard waited for the job
            torrents.joinGate!!.complete(Unit)
            call.join()
        }
        assertEquals(emptyList(), copies.deleted.toList())
    }

    @Test
    fun theCopyIsDeletedEvenWhenTheJobDoesNotEndInTimeButItsFilesStay() {
        val torrents = FakeTorrentEngine().apply { joinGate = CompletableDeferred() } // never completes
        val opener = FakeOpener()
        val folder = Path.of("/data/T1")
        runBlocking {
            runEngineCommand(
                torEnv(torrents, opener = opener),
                EngineCmd.Discard(setOf("1"), listOf(folder), listOf(hex)),
                joinWaitMs = 100,
            )
        }
        assertEquals(emptyList(), opener.trashed.toList(), "a job still writing keeps its files")
        assertEquals(listOf("join:1", "forget:1"), torrents.log.toList())
        assertEquals(listOf(hex), copies.deleted.toList())
    }

    // review m4: the engine skips a copy for a forgotten row, so the row is forgotten before its copy goes, job ended or not
    @Test
    fun theTorrentEngineForgetsTheRowBeforeItsCopyIsDeletedSoAJobStillWindingDownCannotLeaveOneBehind() {
        val torrents = FakeTorrentEngine().apply { joinGate = CompletableDeferred() } // never completes
        val seenAtDelete = java.util.concurrent.CopyOnWriteArrayList<List<String>>()
        val store =
            object : TorrentFileStore by copies {
                override fun delete(hex: String) {
                    seenAtDelete.add(torrents.log.toList())
                    copies.delete(hex)
                }
            }
        runBlocking {
            runEngineCommand(
                DownloadEnv(FakeEngine(), FakeProber(), FakeOpener(), MemoryDiskIo(), torrents = torrents, torrentFiles = store),
                EngineCmd.Discard(setOf("1"), emptyList(), listOf(hex)),
                joinWaitMs = 100,
            )
        }
        assertEquals(listOf(listOf("join:1", "forget:1")), seenAtDelete.toList())
    }
}

/** A disk that records the trees it was asked to delete. */
private class RecordingTreeDisk : MemoryDiskIo() {
    val trees = java.util.concurrent.CopyOnWriteArrayList<Path>()

    override fun deleteTree(p: Path) {
        trees.add(p)
    }
}

/** A torrent engine whose start fails, as one with a bug would. */
private class TorrentEngineThatThrows : dev.anydm.engine.torrent.TorrentEngine {
    override val events = kotlinx.coroutines.flow.MutableSharedFlow<dev.anydm.engine.http.EngineEvent>()

    override fun start(job: TorrentJob) = error("engine bug")

    override fun stop(id: String) = Unit
}
