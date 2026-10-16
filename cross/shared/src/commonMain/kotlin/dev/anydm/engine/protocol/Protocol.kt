package dev.anydm.engine.protocol

/**
 * One kind of source (DIRECT, SITE, TORRENT, FTP), driven by StepRunner (FND-03): [probe] while the job is PENDING,
 * then [plan], [transfer] and [finalize] once the queue gives it a slot. Expected failures are StepResult.Failed (with
 * an ErrorClass, or none when no class fits); bugs throw. Never catch CancellationException: pause and cancel arrive
 * as cancellation, and partial files and watermarks stay for the next run. One instance serves every download of its
 * kind, so keep per-job state in the ProbeResult or on disk. The runner owns the save folder: it creates each planned
 * file's `.anydm` partial, hands it over through TransferIo, and renames FinalizeResult.outputs into place (FIL-07).
 */
interface Protocol {
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
}
