package dev.anydm.desktop

import dev.anydm.engine.http.JobSpec
import dev.anydm.engine.http.doneOf
import dev.anydm.engine.torrent.TorrentJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.nio.file.Path

/** What the engine must do after a queue change. The queue stays the only owner of statuses. */
sealed interface EngineCmd {
    data class Start(
        val job: JobSpec,
    ) : EngineCmd

    data class Stop(
        val id: String,
    ) : EngineCmd

    /** A real torrent row begins, or resumes, in the torrent engine. */
    data class StartTorrent(
        val job: TorrentJob,
    ) : EngineCmd

    data class StopTorrent(
        val id: String,
    ) : EngineCmd

    /**
     * Removes the files of rows that were just removed: once the jobs of [ids] have ended (so a Windows `.part` is closed and a
     * POSIX one cannot be created again), each of [paths] goes to the Trash, or is deleted when the OS has no Trash (a folder with
     * everything in it). A torrent's files are not named here but found from its `.torrent` copy (see [OwnTorrent]), so only the
     * files the torrent made go, never a folder as a whole unless it holds nothing else.
     * The `.torrent` copies of [torrentCopies] (info hashes in hex) go whether or not the jobs have ended, except one that a live
     * row has again (the same torrent added anew meanwhile): a job has read its copy by the time it runs, and the copy is read for
     * [torrents] before it goes.
     */
    data class Discard(
        val ids: Set<String>,
        val paths: List<Path>,
        val torrentCopies: List<String> = emptyList(),
        val torrents: List<OwnTorrent> = emptyList(),
    ) : EngineCmd
}

/** How long a delete waits for a stopped job to end before it leaves that row's files alone. */
const val JOIN_WAIT_MS = 5_000L

/** A row with an http(s) link is a real download for the HTTP engine. */
private fun DownloadItem.realHttp(): Boolean = kind == DownloadKind.Direct && url != null

/** A torrent row with an info hash is a real torrent. Magnets, sample rows and torrent rows from before torrents were real stay simulated. */
internal fun DownloadItem.realTorrent(): Boolean = kind == DownloadKind.Torrent && infoHash != null

fun torrentJobOf(
    item: DownloadItem,
    defaultFolder: String,
    copies: TorrentFileStore,
    peerId: ByteArray,
): TorrentJob =
    TorrentJob(
        item.id,
        copies.path(checkNotNull(item.infoHash)).toString(),
        item.folder ?: defaultFolder,
        item.name,
        // null (a row that never ran) picks a free name; a set (it ran before) keeps the names its files have
        item.pieces,
        peerId,
        doneBytes = item.doneBytes,
    )

fun jobOf(
    item: DownloadItem,
    defaultFolder: String,
): JobSpec =
    JobSpec(
        item.id,
        item.url.orEmpty(),
        item.folder ?: defaultFolder,
        item.name,
        item.doneBytes,
        item.etag,
        item.lastModified,
        item.totalBytes.takeIf { it > 0 },
        item.connections,
        item.spans,
        item.partId,
    )

/** Stops for rows that were Downloading and no longer are (or are gone), then starts for rows that now are Downloading and were not. */
fun engineCommands(
    prev: QueueState,
    next: QueueState,
    defaultFolder: String,
    copies: TorrentFileStore,
    peerId: ByteArray,
): List<EngineCmd> {
    fun running(s: QueueState) =
        s.list.filter { (it.realHttp() || it.realTorrent()) && it.status == DownloadStatus.Downloading }.associateBy { it.id }
    val before = running(prev)
    val after = running(next)
    val stops =
        before.values.filter { it.id !in after }.map {
            if (it.realTorrent()) {
                EngineCmd.StopTorrent(
                    it.id,
                )
            } else {
                EngineCmd.Stop(it.id)
            }
        }
    val starts =
        after.values.filter { it.id !in before }.map {
            if (it.realTorrent()) {
                EngineCmd.StartTorrent(
                    torrentJobOf(it, defaultFolder, copies, peerId),
                )
            } else {
                EngineCmd.Start(jobOf(it, defaultFolder))
            }
        }
    return stops + starts
}

/**
 * Runs one command on the caller's (IO) thread; commands of one queue run one after the other, so a delete never overtakes the
 * stop before it. It never throws: whatever one command hits, the next still runs. A start goes from what is really on disk:
 * the `.part` file's size is the truth (a stopped job may have written past its last report).
 */
suspend fun runEngineCommand(
    env: DownloadEnv,
    cmd: EngineCmd,
    joinWaitMs: Long = JOIN_WAIT_MS,
    /** The info hashes of the rows that exist now, asked at the moment a copy would be deleted: such a copy stays. */
    liveHashes: () -> Set<String> = { emptySet() },
) {
    try {
        when (cmd) {
            is EngineCmd.Stop -> env.engine.stop(cmd.id)
            is EngineCmd.StartTorrent -> env.torrents.start(cmd.job)
            is EngineCmd.StopTorrent -> env.torrents.stop(cmd.id)
            is EngineCmd.Start -> env.engine.start(prepared(env, cmd.job))
            is EngineCmd.Discard -> discard(env, cmd, joinWaitMs, liveHashes)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        // One command failing (a disk error, an engine bug) must not end the consumer: later commands still matter.
    }
}

private suspend fun discard(
    env: DownloadEnv,
    cmd: EngineCmd.Discard,
    joinWaitMs: Long,
    liveHashes: () -> Set<String>,
) {
    // A row is in one engine or the other (an id the engine does not know joins at once), so both are asked.
    val ended =
        cmd.ids.all { id ->
            withTimeoutOrNull(joinWaitMs) {
                env.engine.join(id)
                env.torrents.join(id)
            } != null
        }
    // The rows are gone either way: what the engine kept for them goes too. A job that is still winding down may put its entry back,
    // and a start under a reused id clears that (see DownloadEngine.start), so forgetting early is safe.
    cmd.ids.forEach {
        env.engine.forget(it)
        env.torrents.forget(it)
    }
    // What the torrents made is worked out from their copies, which go right after: so the copies are read first. A job that has not
    // ended may still be writing, so nothing is planned for it.
    val removals = if (ended) cmd.torrents.mapNotNull { planOf(env, it) } else emptyList()
    for (hex in cmd.torrentCopies) {
        // The same torrent added again meanwhile has written this very copy: the new row needs it
        if (hex in liveHashes()) continue
        try {
            env.torrentFiles.delete(hex)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // A copy that cannot go stays in the app's folder, harmless.
        }
    }
    // A job that does not end in time may still be writing: its files stay, and the commands after this one are not held up.
    if (!ended) return
    for (path in cmd.paths) {
        try {
            if (!env.opener.trash(path)) env.disk.deleteTree(path)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // A file that cannot go (in use, no permission) stays; the others still go.
        }
    }
    for (plan in removals) removeTorrentFiles(plan, env.disk, env.opener)
}

/** What the torrent [own] made, from its copy; null when the copy is gone or is no torrent: its files then stay. */
private fun planOf(
    env: DownloadEnv,
    own: OwnTorrent,
): TorrentRemoval? =
    try {
        env.torrentFiles
            .read(own.hex)
            ?.let(::metaOf)
            ?.let { planTorrentRemoval(env.disk, it, own) }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

/**
 * What a start goes from. A one-connection row: the `.part` file's size, the truth (a stopped job may have written past its
 * last report). A segmented row's `.part` is full size from the start (plus the identity trailer), so its stored ranges are the
 * truth instead, and a missing file means zero; whether the file is the one the ranges were made for is the engine's to check. A row on several connections without ranges is left to the engine: the file may be one stream's partial or a
 * pre-sized one with gaps, and only the engine, once the row's predecessor has ended, can tell which.
 */
private fun prepared(
    env: DownloadEnv,
    job: JobSpec,
): JobSpec =
    try {
        val size = env.disk.size(Path.of(job.folder).resolve("${job.name}.part"))
        val spans = job.spans
        when {
            spans != null && size != null -> job.copy(done = doneOf(spans))
            spans != null -> job.copy(done = 0L, spans = null, partId = null)
            else -> job.copy(done = size ?: 0L)
        }
    } catch (_: RuntimeException) {
        job // an unusable folder or name fails inside the engine, with a reason
    } catch (_: IOException) {
        job
    }
