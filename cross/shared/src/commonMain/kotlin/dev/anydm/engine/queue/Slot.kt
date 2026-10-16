package dev.anydm.engine.queue

import dev.anydm.engine.core.Download
import dev.anydm.engine.core.DownloadId
import dev.anydm.engine.core.Mirror
import dev.anydm.engine.core.RunOutcome
import dev.anydm.engine.core.StepFailure
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job

/**
 * One job holding a slot. Everything here but [id], [mirror] and [job] belongs to the queue's coroutine: the slot's
 * own coroutine gets its starting snapshot as a parameter and talks back only through messages.
 */
internal class Slot(
    /** The job as the queue last stored it. */
    var download: Download,
    val mirror: Mirror?,
) {
    val id: DownloadId = download.id
    lateinit var job: Job

    /** The last move the runner reported, applied or not; it decides whether a throw came after the finish. */
    var lastReported: Download? = null

    /** What to do, in order, once the cancelled slot has stopped; not empty means it is stopping. */
    val afterStop = mutableListOf<AfterStop>()

    val stopping: Boolean get() = afterStop.isNotEmpty()
}

/** A command that had to stop the job's slot first: [op] runs on the job as stored then, and answers [reply]. */
internal class AfterStop(
    val op: suspend (Download) -> CommandResult,
    val reply: CompletableDeferred<CommandResult>?,
)

/** How a slot's coroutine ended; it sends exactly one. */
internal sealed interface SlotEnd {
    data class Finished(
        val outcome: RunOutcome.Finished,
    ) : SlotEnd

    /** A step failed and the runner moved the job to [download]. */
    data class Failed(
        val download: Download,
        val failure: StepFailure,
    ) : SlotEnd

    data object Cancelled : SlotEnd

    /** The protocol or the runner threw: a bug, not an expected failure. */
    data class Crashed(
        val error: Throwable,
    ) : SlotEnd
}
