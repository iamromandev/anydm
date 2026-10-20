package dev.anydm.engine.protocol

import dev.anydm.engine.core.Download

/**
 * One kind of source (DIRECT, SITE, TORRENT, FTP), driven by StepRunner (FND-03): [probe] while the job is PENDING,
 * then [plan], [transfer] and [finalize] once the queue gives it a slot. Expected failures are StepResult.Failed (with
 * an ErrorClass, or none when no class fits); bugs throw. Never catch CancellationException: pause and cancel arrive
 * as cancellation, and partial files and watermarks stay for the next run. One instance serves every download of its
 * kind, so keep per-job state in the ProbeResult or on disk. The runner owns the save folder: it creates each planned
 * file's `.anydm` partial, hands it over through TransferIo, and renames FinalizeResult.outputs into place (FIL-07),
 * unless the protocol [ownsFiles].
 */
interface Protocol {
    /**
     * The protocol keeps its own files (a torrent's library writes them and moves them into place, TOR-10). The runner
     * then makes the file rows only: no `.anydm` partial, no preallocation, no segments, no placement. [finalize] says
     * where the files are, relative to the save folder, and they must be there.
     */
    val ownsFiles: Boolean get() = false

    /** What there is: name, size, and whatever [plan] needs (HTTP-01, TOR-02, MED-01). */
    suspend fun probe(request: ProbeRequest): StepResult<ProbeResult>

    /** The files and ranges to fetch. Planning a stored file's ranges again resumes it from its watermarks. */
    fun plan(
        probe: ProbeResult,
        context: PlanContext,
    ): StepResult<TransferPlan>

    /** Fetches into the partials through [io], each segment from its watermark. */
    suspend fun transfer(
        transfer: Transfer,
        io: TransferIo,
    ): StepResult<Unit>

    /** This kind's post-processing (merge, verify, move storage); the runner then renames the outputs. */
    suspend fun finalize(transfer: Transfer): StepResult<FinalizeResult>

    /**
     * Whether the request's url can replace its mirror's address (FND-12 relink): the same content, typically a fresh
     * link for an expired one, checked against the bytes already stored (HTTP-13). Called outside the queue, so it may
     * take a network round trip. Returns the new link's validator. A kind that can't tell keeps this default and refuses.
     */
    suspend fun relink(request: RelinkRequest): StepResult<Relinked> =
        StepResult.Failed(errorClass = null, message = "relink not supported")

    /**
     * Folders (relative to the job's save folder) that this kind keeps its own work in, such as fetched fragments or a
     * downloader's staging area. Removing the job deletes them with everything in them; the finished output never
     * lives there.
     */
    fun workFolders(download: Download): List<String> = emptyList()

    /**
     * The engine-wide limits in bytes per second, null for none (NET-02, TOR-08): told at start and on each change. Only a
     * protocol whose library paces its own sockets needs them; the others have the [Limiter].
     */
    fun setGlobalLimits(
        download: Long?,
        upload: Long?,
    ) = Unit

    /** At start, for a job left SEEDING: takes it up again. A Failed sends the job to FAILED with its message. */
    suspend fun resumeSeeding(download: Download): StepResult<Unit> = StepResult.Ok(Unit)

    /** Called once when the engine stops, after the queue has: release what the protocol holds (a session, a socket). */
    suspend fun close() = Unit
}
