package dev.anydm.engine

import dev.anydm.engine.core.DownloadId
import dev.anydm.engine.core.DownloadStatus
import dev.anydm.engine.core.ErrorClass
import dev.anydm.engine.core.MirrorId
import kotlinx.io.files.Path
import kotlin.time.Instant

/**
 * Something that happened, for whoever reacts to it (a notification, FND-11). Snapshots are the truth about every job;
 * events are moments a snapshot can't show. They are not replayed: a late subscriber reads the snapshots.
 */
sealed interface EngineEvent {
    data class Added(
        val id: DownloadId,
    ) : EngineEvent

    data class Removed(
        val id: DownloadId,
    ) : EngineEvent

    /** Every stored status change, the runner's moves included. */
    data class StatusChanged(
        val id: DownloadId,
        val from: DownloadStatus,
        val to: DownloadStatus,
    ) : EngineEvent

    /** Finished; [outputs] are the files in place (empty when the run ended by a bug after it finished). */
    data class Completed(
        val id: DownloadId,
        val outputs: List<Path>,
    ) : EngineEvent

    /** The job ended FAILED (not a failure that is retried). */
    data class Failed(
        val id: DownloadId,
        val errorClass: ErrorClass?,
        val message: String,
    ) : EngineEvent

    /** It runs again at [at]; [attempt] counts the retries so far. */
    data class RetryScheduled(
        val id: DownloadId,
        val at: Instant,
        val attempt: Int,
    ) : EngineEvent

    data class MovedToMirror(
        val id: DownloadId,
        val mirror: MirrorId,
    ) : EngineEvent

    /** Paused because the disk is full (FIL-08). */
    data class PausedForDisk(
        val id: DownloadId,
    ) : EngineEvent

    /** Paused until the runtime tool [tool] (its id) is installed (BIN-01). */
    data class PausedForTool(
        val id: DownloadId,
        val tool: String,
    ) : EngineEvent

    /** The job waits for [tool] (its id), which can't be fetched on this platform or couldn't be started (BIN-01). */
    data class ToolUnavailable(
        val id: DownloadId,
        val tool: String,
    ) : EngineEvent

    /** Its partial files were thrown away and it starts from zero (#554). */
    data class StartedOver(
        val id: DownloadId,
        val reason: String,
    ) : EngineEvent

    /** stop() closed the store while these jobs were still saving their last checkpoint; they resume from the one before. */
    data class StopTimedOut(
        val stillSaving: List<DownloadId>,
    ) : EngineEvent

    /**
     * A login found in a URL could not be saved in the OS keychain (FTP-06): the download was added and uses it for this
     * run only. [origin] is scheme, host and port; the login itself is never in an event.
     */
    data class CredentialsNotSaved(
        val origin: String,
    ) : EngineEvent

    /** The store failed; the engine has stopped. */
    data class EngineFailed(
        val error: Throwable,
    ) : EngineEvent
}
