package dev.anydm.engine.core

import dev.anydm.engine.core.DownloadStatus.COMPLETED
import dev.anydm.engine.core.DownloadStatus.DOWNLOADING
import dev.anydm.engine.core.DownloadStatus.MUXING
import dev.anydm.engine.core.DownloadStatus.PENDING
import dev.anydm.engine.core.DownloadStatus.QUEUED
import dev.anydm.engine.core.DownloadStatus.SEEDING
import dev.anydm.engine.platform.FileResult
import dev.anydm.engine.platform.FileSystem
import dev.anydm.engine.platform.FileWriter
import dev.anydm.engine.protocol.Limiter
import dev.anydm.engine.protocol.Meter
import dev.anydm.engine.protocol.PlanContext
import dev.anydm.engine.protocol.PlannedFile
import dev.anydm.engine.protocol.ProbeRequest
import dev.anydm.engine.protocol.Protocol
import dev.anydm.engine.protocol.StepResult
import dev.anydm.engine.protocol.StoredBytes
import dev.anydm.engine.protocol.StoredFile
import dev.anydm.engine.protocol.Transfer
import dev.anydm.engine.protocol.TransferFile
import dev.anydm.engine.protocol.TransferPlan
import dev.anydm.engine.store.NewFile
import dev.anydm.engine.store.NewSegment
import dev.anydm.engine.store.PlanStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Runs a job's steps with the [Protocol] of its kind (FND-03). The queue (#552) sits between two calls: [prepare] runs
 * preflight and probe on a PENDING job and moves it to QUEUED; [run] runs transfer, post-process and finish once the
 * queue gives the job a slot: QUEUED, DOWNLOADING, MUXING, then COMPLETED or SEEDING.
 *
 * | Step         | Runs in           | On success                                    | On failure                  |
 * |--------------|-------------------|-----------------------------------------------|-----------------------------|
 * | Preflight    | PENDING           | (probe)                                       | status unchanged            |
 * | Probe        | PENDING           | QUEUED, with saveFolder, fileName, size set   | status unchanged            |
 * | Transfer     | DOWNLOADING       | MUXING                                        | status unchanged            |
 * | Post-process | MUXING            | COMPLETED, or SEEDING if the protocol says so | status unchanged            |
 * | Finish       | COMPLETED/SEEDING | (the finish hook)                             | a throw is a bug, see below |
 *
 * The runner never writes a status: it moves the job with transitionTo and reports each move to the [RunListener]
 * before the step that runs in the new status starts, and #552 persists it. It writes only the plan's file and
 * segment rows ([PlanStore]) and the files in the save folder. A failed step returns the job in the status it failed
 * in, with no move: the queue owns every move after a failure (#553: RetryPolicy). Expected failures come back as
 * outcomes; bugs throw.
 *
 * Cancellation propagates with no move; partials and watermarks stay for the next run. The finish hook is the
 * exception: it runs shielded from cancellation, so a job whose COMPLETED or SEEDING move was reported always returns
 * Finished. If the hook throws, the exception leaves [run] after that move: #552 must treat a throw from `run` after
 * COMPLETED or SEEDING was reported as a finished job.
 *
 * A QUEUED job needs the [PrepareOutcome.Ready] that [prepare] returned, which is in memory only. After a restart, or
 * for a PAUSED job moved to QUEUED, there is none: send the job back through PENDING and [prepare], and let the
 * protocol re-validate its source in that probe (ETag, size).
 *
 * Resume is all or nothing at the level of the plan: a run keeps the stored rows and partials only when the planned
 * file list (index, path, size) equals the stored one. Within an equal list, a file resumes only if its ranges are
 * unchanged, it isn't [PlannedFile.fresh] and its partial opens with the planned length; otherwise that file starts
 * over. A changed list replaces all rows, starts every file over and deletes the partials of files that dropped out.
 * Keeping the unchanged files of a changed list needs a store operation that keeps rows, which the torrent story
 * (#541) can add.
 */
class StepRunner(
    private val fileSystem: FileSystem,
    private val store: PlanStore,
    private val protocols: Map<DownloadKind, Protocol>,
    private val defaultFolder: Path,
    private val duplicates: DuplicateCheck = DuplicateCheck.None,
    private val finish: FinishHook = FinishHook.None,
    private val freeSpaceReserve: Long = DEFAULT_FREE_SPACE_RESERVE,
    private val placement: Placement = DirectPlacement(fileSystem),
    /** How often a running transfer's checkpoints are saved (FND-09); they are also saved when it ends. */
    private val checkpointEvery: Duration = DEFAULT_CHECKPOINT_EVERY,
) {
    init {
        require(checkpointEvery.isPositive()) { "checkpointEvery must be positive: $checkpointEvery" }
        require(defaultFolder.isAbsolute) { "defaultFolder must be absolute: $defaultFolder" }
        require(freeSpaceReserve >= 0) { "freeSpaceReserve must be >= 0: $freeSpaceReserve" }
    }

    /**
     * Preflight and probe a PENDING [download]; on success it is QUEUED and ready for [run]. The room a size needs is
     * checked as soon as it is known: in preflight if the download had a size, and right after the probe if the probe
     * is what learned it (a failure there is a PROBE failure, since the probe's answer is what doesn't fit).
     */
    suspend fun prepare(
        download: Download,
        mirror: Mirror,
        listener: RunListener = RunListener.None,
        others: List<Mirror> = emptyList(),
        /** The stored bytes failed their checksum (the last attempt ended INTEGRITY): a changed source replaces them. */
        failedCheck: Boolean = false,
    ): PrepareOutcome {
        require(download.status == PENDING) { "prepare needs a PENDING download, not ${download.status}" }
        require(mirror.downloadId == download.id) { "mirror ${mirror.id.value} isn't download ${download.id.value}'s" }
        val protocol =
            protocols[download.kind]
                ?: return prepareFailed(
                    download,
                    Step.PREFLIGHT,
                    StepResult.Failed(null, "no protocol handles ${download.kind} downloads yet"),
                )
        val folder =
            when (val checked = preflight(download, mirror)) {
                is StepResult.Failed -> return prepareFailed(download, Step.PREFLIGHT, checked)
                is StepResult.Ok -> checked.value
            }
        val probe =
            when (val probed = protocol.probe(ProbeRequest(download, mirror, store.context(download.id)))) {
                is StepResult.Failed -> return prepareFailed(download, Step.PROBE, probed)
                is StepResult.Ok -> probed.value
            }
        val stored = download.sourceValidator
        var probed = probe.validator
        // Every byte already fetched is one whole copy of the old source (its outputs may be placed): nothing would be
        // appended, so it is kept, still marked with the old validator.
        // A copy that failed its checksum is no copy worth keeping (HTTP-14).
        if (stored != null && probed != null && probed != stored && !failedCheck && allFetched(download.id)) probed = stored
        if (stored != null && probed != null && probed != stored) {
            val dropped = dropPartials(download, folder)
            if (dropped is StepResult.Failed) return prepareFailed(download, Step.PROBE, dropped)
            listener.startedOver("source changed (validator \"$stored\" → \"$probed\"): starting from zero")
        }
        val plan =
            when (val planned = placement.plan(download, mirror, probe, defaultFolder)) {
                is StepResult.Failed -> return prepareFailed(download, Step.PROBE, planned)
                is StepResult.Ok -> planned.value
            }
        duplicates.afterProbe(download, probe, Path(plan.folder, plan.fileName))?.let { found ->
            val reason =
                found.existing?.let { "already in the list as download ${it.value}" }
                    ?: "already on disk: ${Path(plan.folder, plan.fileName)}"
            return prepareFailed(download, Step.PROBE, StepResult.Failed(null, reason))
        }
        if (plan.folder != folder) {
            val prepared = fileSystem.prepareFolder(plan.folder)
            if (prepared is FileResult.Failed) return prepareFailed(download, Step.PROBE, prepared.toStepFailed())
        }
        val learned = probe.size
        if (learned != null && (learned != download.size || plan.folder != folder)) {
            val room = checkSpace(plan.folder, learned - writtenBytes(download.id))
            if (room is StepResult.Failed) return prepareFailed(download, Step.PROBE, room)
        }
        // The user's file name wins; the probe's size is the source's own word.
        val queued =
            download
                .copy(
                    categoryId = plan.categoryId,
                    saveFolder = plan.folder.toString(),
                    fileName = plan.fileName,
                    size = learned ?: download.size,
                    sourceValidator = probed ?: stored,
                ).moveTo(QUEUED)
        listener.moved(queued)
        return PrepareOutcome.Ready(queued, mirror, probe, others)
    }

    /**
     * What earlier runs of [download] fetched, for a relink to compare with (HTTP-13): each stored file's ranges below
     * its saved watermarks, touching ones merged (one range spans many blocks), read from its partial. A file whose
     * partial is gone, or a job without a save folder, has nothing written: the next run would start it over anyway.
     */
    suspend fun storedBytes(download: Download): StoredBytes {
        val folder = download.saveFolder?.let(::Path) ?: return PartialBytes(fileSystem, emptyList(), emptyMap())
        val files = mutableListOf<StoredFile>()
        val partials = mutableMapOf<Int, Path>()
        for (file in store.files(download.id)) {
            val partial = partialPath(targetPath(folder, file.path), download.id)
            // Only a missing partial is nothing written: one the disk can't stat now still fails the compare.
            val present = fileSystem.size(partial) !is FileResult.NotFound
            val written =
                if (present) {
                    mergedRanges(store.segments(file.id).filter { it.watermark > it.start }.map { it.start until it.watermark })
                } else {
                    emptyList()
                }
            files += StoredFile(file.index, file.size, written)
            partials[file.index] = partial
        }
        return PartialBytes(fileSystem, files, partials)
    }

    /** True when the download has stored files and every one of their segments is written to its end. */
    private suspend fun allFetched(id: DownloadId): Boolean {
        val files = store.files(id)
        return files.isNotEmpty() && files.all { file -> store.segments(file.id).all { it.watermark == it.end } }
    }

    /**
     * The source is no longer the one the partials came from (FND-10): never append to them. Deletes each stored file's
     * partial (one already gone is fine) and the file rows with their watermarks, so the plan starts every file over.
     */
    private suspend fun dropPartials(
        download: Download,
        folder: Path,
    ): StepResult<Unit> {
        for (file in store.files(download.id)) {
            val deleted = fileSystem.delete(partialPath(targetPath(folder, file.path), download.id))
            if (deleted is FileResult.Failed && deleted !is FileResult.NotFound) return deleted.toStepFailed()
        }
        store.replaceFiles(download.id, emptyList())
        return StepResult.Ok(Unit)
    }

    /** Transfer, post-process and finish a QUEUED job that [prepare] made ready. */
    suspend fun run(
        job: PrepareOutcome.Ready,
        listener: RunListener = RunListener.None,
        limiter: Limiter = Limiter.None,
        meter: Meter = Meter.None,
    ): RunOutcome {
        require(job.download.status == QUEUED) { "run needs a QUEUED download, not ${job.download.status}" }
        val folder = Path(requireNotNull(job.download.saveFolder) { "a prepared download has a save folder" })
        require(folder.isAbsolute) { "save folder must be absolute: $folder" }
        val protocol = protocols.getValue(job.download.kind)
        val downloading = job.download.moveTo(DOWNLOADING)
        listener.moved(downloading)
        val transfer =
            when (val done = transferStep(downloading, job, protocol, folder, listener, limiter, meter)) {
                is StepResult.Failed -> return runFailed(downloading, Step.TRANSFER, done)
                is StepResult.Ok -> done.value
            }
        val muxing = downloading.moveTo(MUXING)
        listener.moved(muxing)
        val finalized =
            when (val done = protocol.finalize(transfer)) {
                is StepResult.Failed -> return runFailed(muxing, Step.POST_PROCESS, done)
                is StepResult.Ok -> done.value
            }
        val expected = job.download.checksum ?: job.probe.checksum
        // Only one file has one checksum; bad output paths are placeOutputs' to refuse, before anything reads them.
        if (expected != null && finalized.outputs.size == 1 && pathProblem(finalized.outputs) == null) {
            val target = targetPath(folder, finalized.outputs.single())
            val checked = checkFile(partialPath(target, job.download.id), target, expected)
            if (checked is StepResult.Failed) return runFailed(muxing, Step.POST_PROCESS, checked)
        }
        val outputs =
            when (val placed = placeOutputs(job.download, folder, finalized.outputs)) {
                is StepResult.Failed -> return runFailed(muxing, Step.POST_PROCESS, placed)
                is StepResult.Ok -> placed.value
            }
        // A name clash can have renamed the one file: the job's name follows it.
        val renamedTo = outputs.singleOrNull()?.takeIf { it != targetPath(folder, finalized.outputs.single()) }?.name
        val finished =
            (
                if (renamedTo !=
                    null
                ) {
                    muxing.copy(fileName = renamedTo)
                } else {
                    muxing
                }
            ).moveTo(if (finalized.seeding) SEEDING else COMPLETED)
        listener.moved(finished)
        // The job is finished whatever happens from here, so a pause or cancel must not interrupt its hook.
        withContext(NonCancellable) { finish.finished(finished, outputs) }
        return RunOutcome.Finished(finished, outputs)
    }

    /** The save folder is checked and made before the probe, so a read-only disk fails early. */
    private suspend fun preflight(
        download: Download,
        mirror: Mirror,
    ): StepResult<Path> {
        val folder = download.saveFolder?.let(::Path) ?: defaultFolder
        if (!folder.isAbsolute) return StepResult.Failed(null, "the save folder must be a full path: $folder")
        val prepared = fileSystem.prepareFolder(folder)
        if (prepared is FileResult.Failed) return prepared.toStepFailed()
        if (download.size != null) {
            val room = checkSpace(folder, download.size - writtenBytes(download.id))
            if (room is StepResult.Failed) return room
        }
        return StepResult.Ok(folder)
    }

    /**
     * Plan, store it, open the partials, transfer. Every writer is closed on every exit: a close that fails is added to
     * the exception already on its way, and surfaces by itself only if nothing else went wrong. The original exception
     * (cancellation included) is always the one rethrown.
     */
    private suspend fun transferStep(
        download: Download,
        job: PrepareOutcome.Ready,
        protocol: Protocol,
        folder: Path,
        listener: RunListener,
        limiter: Limiter,
        meter: Meter,
    ): StepResult<Transfer> {
        val stored =
            store.files(download.id).map { file ->
                val target = targetPath(folder, file.path)
                TransferFile(file, store.segments(file.id), partialPath(target, download.id), target)
            }
        val plan =
            when (val planned = protocol.plan(job.probe, PlanContext(download, stored))) {
                is StepResult.Failed -> return planned
                is StepResult.Ok -> planned.value
            }
        val writers = mutableMapOf<FileId, FileWriter>()
        val owners = mutableMapOf<SegmentId, FileId>()
        val checkpointer = Checkpointer(writers, store, owners) { listener.checkpointed() }
        val result =
            try {
                fetch(download, job, protocol, plan, stored, folder, listener, limiter, meter, writers, owners, checkpointer)
            } catch (e: Throwable) {
                // A pause, cancel or quit (or a bug) still keeps what was written: everything is durable up to here.
                try {
                    val flushed = withContext(NonCancellable) { checkpointer.flush() }
                    if (flushed is StepResult.Failed) e.addSuppressed(IllegalStateException("final checkpoint failed: ${flushed.message}"))
                } catch (flushError: Throwable) {
                    e.addSuppressed(flushError)
                }
                closeAll(writers.values, e)
                throw e
            }
        // On success this is what makes the transfer durable before finalize and the rename build on it.
        val flushed =
            try {
                withContext(NonCancellable) { checkpointer.flush() }
            } catch (e: Throwable) {
                closeAll(writers.values, e)
                throw e
            }
        closeAll(writers.values, null)
        return if (result is StepResult.Ok && flushed is StepResult.Failed) flushed else result
    }

    private suspend fun fetch(
        download: Download,
        job: PrepareOutcome.Ready,
        protocol: Protocol,
        plan: TransferPlan,
        stored: List<TransferFile>,
        folder: Path,
        listener: RunListener,
        limiter: Limiter,
        meter: Meter,
        writers: MutableMap<FileId, FileWriter>,
        owners: MutableMap<SegmentId, FileId>,
        checkpointer: Checkpointer,
    ): StepResult<Transfer> {
        val transfer =
            when (val opened = openFiles(download, job, plan, stored, folder, writers)) {
                is StepResult.Failed -> return opened
                is StepResult.Ok -> opened.value
            }
        for (part in transfer.files) part.segments.forEach { owners[it.id] = part.file.id }
        val total = if (plan.files.all { it.size != null }) plan.files.map { it.size ?: 0L }.saturatingSum() else null
        val io = RunIo(transfer, writers, checkpointer, limiter, meter, listener, total)
        val moved = transferWithCheckpoints(protocol, transfer, io, checkpointer)
        if (moved is StepResult.Failed) return moved
        return StepResult.Ok(transfer)
    }

    /**
     * Runs the protocol's transfer beside a ticker that saves its checkpoints every [checkpointEvery]. A tick that can't
     * sync the files stops the transfer, and its failure is the step's.
     */
    private suspend fun transferWithCheckpoints(
        protocol: Protocol,
        transfer: Transfer,
        io: RunIo,
        checkpointer: Checkpointer,
    ): StepResult<Unit> {
        // coroutineScope may hand back a copy of what its body threw (stack trace recovery): keep the original.
        var thrown: Throwable? = null
        return try {
            coroutineScope {
                val ticker =
                    launch {
                        while (true) {
                            delay(checkpointEvery)
                            val flushed = checkpointer.flush()
                            if (flushed is StepResult.Failed) throw TickFailed(flushed)
                        }
                    }
                try {
                    protocol.transfer(transfer, io)
                } catch (e: Throwable) {
                    thrown = e
                    throw e
                } finally {
                    ticker.cancel()
                }
            }
        } catch (e: TickFailed) {
            e.failure
        } catch (e: Throwable) {
            // The body's cancellation is only a symptom when the ticker failed for real (the store, say).
            throw thrown?.takeUnless { it is CancellationException && e !is CancellationException } ?: e
        }
    }

    // How a failed tick stops the transfer beside it: it fails the scope, which cancels the transfer.
    private class TickFailed(
        val failure: StepResult.Failed,
    ) : Exception(failure.message)

    /** Closes every writer, even if some close throws; see [transferStep] for what is thrown. */
    private fun closeAll(
        writers: Collection<FileWriter>,
        primary: Throwable?,
    ) {
        var first: Throwable? = null
        for (writer in writers) {
            try {
                writer.close()
            } catch (e: Throwable) {
                when {
                    primary != null -> primary.addSuppressed(e)
                    first == null -> first = e
                    else -> first.addSuppressed(e)
                }
            }
        }
        if (first != null) throw first
    }

    /**
     * Stores [plan] and readies each partial. The stored rows and partials are reused only when the planned file list
     * (index, path, size) equals the stored one; then each file whose ranges are unchanged, that is not
     * [PlannedFile.fresh] and whose partial opens with the planned length resumes, and every other file starts over
     * (new segments, the partial deleted and made again). A different file list replaces all rows, starts every file
     * over and first deletes the partials of stored files that are not in the new plan. Per-file resume across a changed
     * list is left to the torrent story (#541).
     */
    private suspend fun openFiles(
        download: Download,
        job: PrepareOutcome.Ready,
        plan: TransferPlan,
        stored: List<TransferFile>,
        folder: Path,
        writers: MutableMap<FileId, FileWriter>,
    ): StepResult<Transfer> {
        val planned = plan.files.sortedBy { it.index }
        pathProblem(planned.map { it.path })?.let { return StepResult.Failed(null, "the plan can't be made in the save folder: $it") }
        val sameFiles = stored.map { it.file.key() } == planned.map { it.key() }
        val earlier = if (sameFiles) stored else emptyList()
        val kept =
            earlier
                .zip(planned)
                .filter { (old, new) ->
                    resumes(old, new)
                }.map { (old, _) -> old.segments.sumOf { it.written } }
                .saturatingSum()
        val room = checkSpace(folder, planned.map { it.size ?: 0L }.saturatingSum() - kept)
        if (room is StepResult.Failed) return room
        if (!sameFiles) {
            val targets = planned.map { targetPath(folder, it.path) }.toSet()
            for (old in stored.filter { it.target !in targets && isSafeRelativePath(it.file.path) }) {
                val gone = fileSystem.delete(old.partial)
                if (gone is FileResult.Failed && gone !is FileResult.NotFound) return gone.toStepFailed()
            }
        }
        for (parent in planned.map { requireNotNull(targetPath(folder, it.path).parent) }.distinct()) {
            val made = fileSystem.prepareFolder(parent)
            if (made is FileResult.Failed) return made.toStepFailed()
        }
        val rows =
            if (sameFiles) {
                stored.map { it.file }
            } else {
                store.replaceFiles(
                    download.id,
                    planned.map { NewFile(it.index, it.path, it.size) },
                )
            }
        val files = mutableListOf<TransferFile>()
        for ((i, new) in planned.withIndex()) {
            val file = rows[i]
            val target = targetPath(folder, new.path)
            val partial = partialPath(target, download.id)
            val old = earlier.getOrNull(i)
            if (old != null && resumes(old, new) && alreadyPlaced(old.segments, partial, target, new.size)) {
                // Placed by a run that stopped while placing outputs: nothing to write, nothing to open.
                files += TransferFile(file, old.segments, partial, target)
                continue
            }
            val resumed = if (old != null && resumes(old, new)) reopen(partial, new.size) else null
            if (resumed is StepResult.Failed) return resumed
            val writer: FileWriter
            val segments: List<Segment>
            if (resumed is StepResult.Ok && old != null) {
                writer = resumed.value
                segments = old.segments
            } else {
                segments = store.replaceSegments(file.id, new.segments)
                writer =
                    when (val made = freshPartial(partial, new.size)) {
                        is StepResult.Failed -> return made
                        is StepResult.Ok -> made.value
                    }
            }
            writers[file.id] = writer
            files += TransferFile(file, segments, partial, target)
        }
        return StepResult.Ok(Transfer(download, job.mirror, job.probe, files, job.others))
    }

    /**
     * A file a stopped run already renamed into place: every segment written, no partial left, and the output there with
     * the planned [size]. Without a known size it can't be told from some other file, so it is fetched again.
     */
    private suspend fun alreadyPlaced(
        segments: List<Segment>,
        partial: Path,
        target: Path,
        size: Long?,
    ): Boolean =
        size != null &&
            segments.all { it.watermark == it.end } &&
            fileSystem.size(partial) is FileResult.NotFound &&
            fileSystem.size(target) == FileResult.Ok(size)

    /**
     * The earlier partial opened again, or null when it is gone or its length isn't the planned [size] (some other
     * program's file, or a copy that was cut short: nothing in it can be trusted).
     */
    private suspend fun reopen(
        partial: Path,
        size: Long?,
    ): StepResult<FileWriter>? {
        val writer =
            when (val opened = fileSystem.openWriter(partial)) {
                is FileResult.Ok -> opened.value
                is FileResult.NotFound -> return null
                is FileResult.Failed -> return opened.toStepFailed()
            }
        var keep = false
        try {
            when (val length = writer.size()) {
                is FileResult.Failed -> return length.toStepFailed()
                is FileResult.Ok -> if (length.value != size) return null
            }
            keep = true
            return StepResult.Ok(writer)
        } finally {
            if (!keep) writer.close()
        }
    }

    /** A fresh partial of [size] bytes (sparse where possible) replacing any stale one, opened for writing (HTTP-16). */
    private suspend fun freshPartial(
        partial: Path,
        size: Long?,
    ): StepResult<FileWriter> {
        val deleted = fileSystem.delete(partial)
        if (deleted is FileResult.Failed && deleted !is FileResult.NotFound) return deleted.toStepFailed()
        val made = fileSystem.preallocate(partial, size ?: 0)
        if (made is FileResult.Failed) return made.toStepFailed()
        return when (val opened = fileSystem.openWriter(partial)) {
            is FileResult.Failed -> opened.toStepFailed()
            is FileResult.Ok -> StepResult.Ok(opened.value)
        }
    }

    /**
     * Renames each output's partial into place (FIL-07) and never replaces a file that is already there: that fails
     * the step with the reason and leaves the partial, until the name-clash policy of FIL-04 (#539) decides. The
     * outputs are checked like planned paths first.
     *
     * Outputs are placed one by one, so a failure or a cancel part way leaves the ones already placed without a
     * partial. The next run counts those as placed: openFiles keeps their rows and opens nothing, and here an output
     * whose partial is gone but whose target is there is skipped (#554).
     */
    private suspend fun placeOutputs(
        download: Download,
        folder: Path,
        outputs: List<String>,
    ): StepResult<List<Path>> {
        pathProblem(outputs)?.let { return StepResult.Failed(null, "the outputs can't be placed in the save folder: $it") }
        val placed = mutableListOf<Path>()
        for (output in outputs) {
            val target = targetPath(folder, output)
            val partial = partialPath(target, download.id)
            val moved = placement.place(download, partial, target)
            // No partial but the output is there: a stopped run placed it already.
            if (moved is FileResult.NotFound && moved.path == partial && fileSystem.size(target) is FileResult.Ok) {
                placed += target
                continue
            }
            if (moved is FileResult.Failed) return moved.toStepFailed()
            placed += (moved as FileResult.Ok).value
        }
        return StepResult.Ok(placed)
    }

    /**
     * Hashes [partial] in [CHECK_CHUNK] reads and compares it with [expected] (HTTP-14): INTEGRITY when it differs. An
     * output a stopped run already placed (no partial, [target] there) was checked then and isn't read again.
     */
    private suspend fun checkFile(
        partial: Path,
        target: Path,
        expected: Checksum,
    ): StepResult<Unit> {
        val hasher = hasherFor(expected.algorithm)
        var offset = 0L
        while (true) {
            val bytes =
                when (val read = fileSystem.read(partial, offset, CHECK_CHUNK)) {
                    is FileResult.Ok -> {
                        read.value
                    }

                    is FileResult.NotFound -> {
                        if (offset == 0L && fileSystem.size(target) is FileResult.Ok) return StepResult.Ok(Unit)
                        return read.toStepFailed()
                    }

                    is FileResult.Failed -> {
                        return read.toStepFailed()
                    }
                }
            hasher.update(bytes)
            offset += bytes.size
            if (bytes.size < CHECK_CHUNK) break
        }
        val actual = hasher.hex()
        if (actual == expected.hex) return StepResult.Ok(Unit)
        val name = expected.toString().substringBefore(':')
        return StepResult.Failed(ErrorClass.INTEGRITY, "$name mismatch: expected ${expected.hex}, got $actual")
    }

    /**
     * Throws away what earlier runs fetched, so the next run starts from zero: each stored file's partial and the file
     * rows with their watermarks. The queue's one retry after a checksum mismatch (HTTP-14). Nothing to drop without a
     * save folder.
     */
    suspend fun startOver(download: Download): StepResult<Unit> {
        val folder = download.saveFolder?.let(::Path) ?: return StepResult.Ok(Unit)
        return dropPartials(download, folder)
    }

    /** Room for [bytes] more plus the reserve (FIL-08); nothing to check for 0 or fewer. */
    private suspend fun checkSpace(
        folder: Path,
        bytes: Long,
    ): StepResult<Unit> {
        if (bytes <= 0) return StepResult.Ok(Unit)
        val needed = listOf(bytes, freeSpaceReserve).saturatingSum()
        return when (val free = fileSystem.freeSpace(folder)) {
            is FileResult.Failed -> {
                free.toStepFailed()
            }

            is FileResult.Ok -> {
                if (free.value >= needed) {
                    StepResult.Ok(Unit)
                } else {
                    StepResult.Failed(ErrorClass.DISK_FULL, "needs $needed bytes free in $folder, ${free.value} are free")
                }
            }
        }
    }

    private suspend fun writtenBytes(id: DownloadId): Long =
        store
            .files(id)
            .map { file ->
                store.segments(file.id).sumOf {
                    it.written
                }
            }.saturatingSum()

    private fun prepareFailed(
        download: Download,
        step: Step,
        result: StepResult.Failed,
    ): PrepareOutcome = PrepareOutcome.Failed(download, result.toFailure(step))

    private fun runFailed(
        download: Download,
        step: Step,
        result: StepResult.Failed,
    ): RunOutcome = RunOutcome.Failed(download, result.toFailure(step))

    companion object {
        /** How much of a finished file each read takes while it is hashed (HTTP-14). */
        internal const val CHECK_CHUNK = 1 shl 20

        /** How often a running transfer saves its checkpoints (FND-09). */
        val DEFAULT_CHECKPOINT_EVERY: Duration = 15.seconds

        /** Left free after a download's bytes (FIL-08); #539 makes it a setting. */
        const val DEFAULT_FREE_SPACE_RESERVE: Long = 64L * 1024 * 1024
    }
}

/** [ranges] sorted, with each one that starts where the previous ends joined to it. */
private fun mergedRanges(ranges: List<LongRange>): List<LongRange> {
    val merged = mutableListOf<LongRange>()
    for (range in ranges.sortedBy { it.first }) {
        val last = merged.lastOrNull()
        if (last != null && last.last + 1 == range.first) merged[merged.size - 1] = last.first..range.last else merged += range
    }
    return merged
}

private fun StepResult.Failed.toFailure(step: Step) = StepFailure(step, errorClass, message, retryAfter)

private fun File.key() = Triple(index, path, size)

private fun PlannedFile.key() = Triple(index, path, size)

private fun resumes(
    old: TransferFile,
    new: PlannedFile,
) = !new.fresh && new.segments.isNotEmpty() && old.segments.map { NewSegment(it.start, it.end) } == new.segments

/** transitionTo for moves made only from statuses the runner checked; a rejection is a bug. */
private fun Download.moveTo(target: DownloadStatus): Download =
    when (val moved = transitionTo(target)) {
        is TransitionResult.Moved -> moved.download
        is TransitionResult.Rejected -> error("can't move a download from ${moved.from} to ${moved.to}")
    }
