package dev.anydm.engine.torrent

import dev.anydm.engine.TorrentDetailResult
import dev.anydm.engine.core.DownloadKind
import dev.anydm.engine.core.DownloadStatus
import dev.anydm.engine.queue.CommandResult
import dev.anydm.engine.store.NewDownload
import dev.anydm.engine.store.Settings
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

/**
 * What a front end reads of a torrent (TOR-07), through the real engine and libtorrent on loopback: the summary in the
 * snapshot, the progress of each file kept in the store, and the peer list on demand.
 */
class TorrentDetailEndToEndTest : TorrentEngineTestBase() {
    private val mib = 1024 * 1024

    @Test
    fun aSeedShowsItsPeerAndUploadInTheSnapshot() =
        runBlocking<Unit> {
            val (_, made) = single(size = 3 * mib, seed = 11)
            seed(made)
            newEngine(settings = { it.copy(seedRatio = 100.0) })
            val id = add(made)
            await(id, DownloadStatus.SEEDING)

            // two seeds drop each other: the leecher is slowed so the engine still has it as a peer when the summary is read
            val leeching = startLeech(made, limit = 512L * 1024)
            val seen = awaitSnapshot(id) { (it.torrent?.peers ?: 0) >= 1 && (it.torrent?.uploaded ?: 0) > 0 }
            finishLeech(leeching)

            val summary = seen.torrent!!
            (summary.ratio!! > 0.0) shouldBe true
            summary.seeds shouldBe 0
            (seen.files.single().written == (3 * mib).toLong()) shouldBe true
        }

    @Test
    fun torrentDetailListsTheLeecher() =
        runBlocking<Unit> {
            val (_, made) = single(size = 3 * mib, seed = 12)
            seed(made)
            newEngine(settings = { it.copy(seedRatio = 100.0) })
            val id = add(made)
            await(id, DownloadStatus.SEEDING)
            val leeching = startLeech(made, limit = 512L * 1024)

            var detail: TorrentDetail? = null
            repeat(200) {
                if (detail?.peers?.isNotEmpty() != true) {
                    detail = (engine!!.torrentDetail(id) as? TorrentDetailResult.Detail)?.detail
                    delay(100)
                }
            }
            finishLeech(leeching)

            val shown = detail!!
            // libtorrent may write a loopback peer as an IPv6-mapped address
            shown.peers
                .single()
                .address
                .contains("127.0.0.1") shouldBe true
            shown.pieces.haveCount shouldBe shown.pieces.count
            (shown.pieces.count > 0) shouldBe true
        }

    @Test
    fun aPausedDownloadShowsItsProgress() =
        runBlocking<Unit> {
            val (_, made) = single(size = 4 * mib, seed = 13)
            seed(made)
            newEngine()
            val id = add(made, template = NewDownload(DownloadKind.TORRENT, speedLimit = 256L * 1024))
            awaitSnapshot(id, within = 60.seconds) { it.downloaded >= mib }

            engine!!.pause(id).shouldBeInstanceOf<CommandResult.Done>()
            await(id, DownloadStatus.PAUSED)

            // the file rows are read again once the run has ended, a moment after the job shows PAUSED
            val paused = awaitSnapshot(id) { it.download.status == DownloadStatus.PAUSED && (it.files.singleOrNull()?.written ?: 0) > 0 }
            (paused.files.single().written >= mib) shouldBe true
            paused.downloaded shouldBe paused.files.single().written
            paused.torrent shouldBe null
        }

    @Test
    fun progressSurvivesAnEngineRestart() =
        runBlocking<Unit> {
            val (_, made) = single(size = 4 * mib, seed = 14)
            seed(made)
            newEngine()
            val id = add(made, template = NewDownload(DownloadKind.TORRENT, speedLimit = 256L * 1024))
            awaitSnapshot(id, within = 60.seconds) { it.downloaded >= mib }
            engine!!.pause(id).shouldBeInstanceOf<CommandResult.Done>()
            await(id, DownloadStatus.PAUSED)
            // what the pause saved: libtorrent counts the partly fetched pieces too, so it is more than the pieces done
            val before =
                awaitSnapshot(
                    id,
                ) { it.download.status == DownloadStatus.PAUSED && (it.files.singleOrNull()?.written ?: 0) > 0 }.downloaded
            before shouldBe
                store!!
                    .torrents
                    .get(id)!!
                    .fileDone
                    .sum()
            engine!!.stop()

            newEngine()

            val after = awaitSnapshot(id) { it.download.status == DownloadStatus.PAUSED }
            after.downloaded shouldBe before
            after.files.single().written shouldBe before
        }

    @Test
    fun aFinishedTorrentLeavesNoSummary() =
        runBlocking<Unit> {
            val size = mib
            val (_, made) = single(size = size, seed = 15)
            seed(made)
            newEngine(settings = { Settings() })
            val id = add(made)
            await(id, DownloadStatus.COMPLETED)
            // the summary is cleared on the engine's next look, within a second
            delay(2500)

            val done = awaitSnapshot(id) { it.download.status == DownloadStatus.COMPLETED }
            done.torrent shouldBe null
            done.files.single().written shouldBe size.toLong()
            done.downloaded shouldBe size.toLong()
        }
}
