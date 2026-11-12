package dev.anydm.desktop

import dev.anydm.engine.http.JobSpec
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

    /**
     * Removes the files of rows that were just removed: once the jobs of [ids] have ended (so a Windows `.part` is closed and a
     * POSIX one cannot be created again), each of [paths] goes to the Trash, or is deleted when the OS has no Trash.
     */
    data class Discard(
        val ids: Set<String>,
        val paths: List<Path>,
    ) : EngineCmd
}

/** How long a delete waits for a stopped job to end before it leaves that row's files alone. */
const val JOIN_WAIT_MS = 5_000L

/** Only rows with an http(s) link are real downloads; torrent rows and sample rows stay simulated and never reach the engine. */
private fun DownloadItem.real(): Boolean = kind == DownloadKind.Direct && url != null

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
    )

/** Stops for rows that were Downloading and no longer are (or are gone), then starts for rows that now are Downloading and were not. */
fun engineCommands(
    prev: QueueState,
    next: QueueState,
    defaultFolder: String,
): List<EngineCmd> {
    val before = prev.list.filter { it.real() && it.status == DownloadStatus.Downloading }.associateBy { it.id }
    val after = next.list.filter { it.real() && it.status == DownloadStatus.Downloading }.associateBy { it.id }
    val stops = before.keys.filter { it !in after }.map { EngineCmd.Stop(it) }
    val starts = after.values.filter { it.id !in before }.map { EngineCmd.Start(jobOf(it, defaultFolder)) }
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
) {
    try {
        when (cmd) {
            is EngineCmd.Stop -> env.engine.stop(cmd.id)
            is EngineCmd.Start -> env.engine.start(prepared(env, cmd.job))
            is EngineCmd.Discard -> discard(env, cmd, joinWaitMs)
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
) {
    // A job that does not end in time may still be writing: its files stay, and the commands after this one are not held up.
    for (id in cmd.ids) if (withTimeoutOrNull(joinWaitMs) { env.engine.join(id) } == null) return
    for (path in cmd.paths) {
        try {
            if (!env.opener.trash(path)) env.disk.delete(path)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // A file that cannot go (in use, no permission) stays; the others still go.
        }
    }
}

private fun prepared(
    env: DownloadEnv,
    job: JobSpec,
): JobSpec =
    try {
        job.copy(done = env.disk.size(Path.of(job.folder).resolve("${job.name}.part")) ?: 0L)
    } catch (_: RuntimeException) {
        job // an unusable folder or name fails inside the engine, with a reason
    } catch (_: IOException) {
        job
    }
