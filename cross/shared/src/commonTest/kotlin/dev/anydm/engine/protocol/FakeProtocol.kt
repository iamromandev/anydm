package dev.anydm.engine.protocol

import dev.anydm.engine.core.Checksum
import dev.anydm.engine.core.Download
import dev.anydm.engine.core.ErrorClass
import dev.anydm.engine.core.Segment
import dev.anydm.engine.store.NewSegment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

internal data class FakeFile(
    val path: String,
    val length: Long,
    val sizeKnown: Boolean = true,
)

internal sealed interface FakeFailure {
    data class AtProbe(
        val failure: StepResult.Failed,
    ) : FakeFailure

    data class AtPlan(
        val failure: StepResult.Failed,
    ) : FakeFailure

    /** After exactly [afterBytes] bytes of this run (or at its end). */
    data class AtTransfer(
        val afterBytes: Long,
        val failure: StepResult.Failed,
    ) : FakeFailure

    data class AtFinalize(
        val failure: StepResult.Failed,
    ) : FakeFailure

    /** A protocol bug: probe throws. */
    data class ThrowAtProbe(
        val error: Throwable,
    ) : FakeFailure
}

internal data class FakeProbe(
    override val name: String,
    override val size: Long?,
    override val validator: String? = null,
    override val checksum: Checksum? = null,
    override val mime: String? = null,
) : ProbeResult

internal data class Fetched(
    val index: Int,
    val start: Long,
    val end: Long,
)

/**
 * A scripted Protocol for every engine test: serves [files] at [bytesPerSecond] of virtual time in [chunk]-byte reads,
 * [segments] ranges per file of known size, one after another, through the limiter, meter and sink, checkpointing
 * each segment after each chunk; fails where [failure] says. Change the vars between runs to script a resume.
 */
internal class FakeProtocol(
    var files: List<FakeFile> = listOf(FakeFile("file.zip", 64L * 1024)),
    var name: String? = null,
    var failure: FakeFailure? = null,
    var segments: Int = 2,
    var chunk: Int = 4 * 1024,
    var bytesPerSecond: Long = 16L * 1024,
    var outputs: List<String>? = null,
    /** What finalize reports as merged away ([FinalizeResult.consumed]). */
    var consumed: List<String> = emptyList(),
    /** What finalize reports as folders to remove once the job is complete ([FinalizeResult.cleanup]). */
    var cleanup: List<String> = emptyList(),
    /** Called with the transfer's io before anything is fetched: lets a test look at what the runner handed over. */
    var onTransfer: (suspend (TransferIo) -> Unit)? = null,
    /** The folders [workFolders] reports (relative to the save folder). */
    var workFolderNames: List<String> = emptyList(),
    var seeding: Boolean = false,
    /** Plans every file as [PlannedFile.fresh], as a protocol that saw its source change would. */
    var fresh: Boolean = false,
    /** What the probe says the source is (ProbeResult.validator); null = can't tell. */
    var validator: String? = null,
    /** What the probe says the source hashes to (ProbeResult.checksum). */
    var checksum: Checksum? = null,
    /** How long a cancelled transfer takes to stop, so a test can see a slot while it is stopping. */
    var stopDelay: Duration = Duration.ZERO,
    /** What [relink] answers; null keeps the interface's default (not supported). */
    var relinkAnswer: StepResult<Relinked>? = null,
    /** When set, [relink] waits for it first. */
    var relinkGate: CompletableDeferred<Unit>? = null,
    /** Mirror urls whose probe answers GONE. */
    var gone: Set<String> = emptySet(),
    /** What the probe says the content type is (ProbeResult.mime). */
    var mime: String? = null,
    private val journal: MutableList<String>? = null,
) : Protocol {
    val calls = mutableListOf<String>()
    val fetched = mutableListOf<Fetched>()

    /** Each probe's mirror url, in order. */
    val probed = mutableListOf<String>()

    /** Every transfer, to check what the runner passed (its other mirrors, #572). */
    val transfers = mutableListOf<Transfer>()

    /** Every probe request, to check what the runner passed (its request context, #567). */
    val requests = mutableListOf<ProbeRequest>()

    override suspend fun probe(request: ProbeRequest): StepResult<ProbeResult> {
        log("probe")
        requests += request
        probed += request.mirror.url
        if (request.mirror.url in gone) return StepResult.Failed(ErrorClass.GONE, "gone: ${request.mirror.url}")
        when (val scripted = failure) {
            is FakeFailure.AtProbe -> return scripted.failure
            is FakeFailure.ThrowAtProbe -> throw scripted.error
            else -> Unit
        }
        val size =
            if (files.all { it.sizeKnown }) {
                files.fold(0L) { sum, file ->
                    if (sum >
                        Long.MAX_VALUE - file.length
                    ) {
                        Long.MAX_VALUE
                    } else {
                        sum + file.length
                    }
                }
            } else {
                null
            }
        return StepResult.Ok(FakeProbe(name ?: files.first().path.substringAfterLast('/'), size, validator, checksum, mime))
    }

    override fun plan(
        probe: ProbeResult,
        context: PlanContext,
    ): StepResult<TransferPlan> {
        log("plan")
        (failure as? FakeFailure.AtPlan)?.let { return it.failure }
        val single = files.size == 1
        return StepResult.Ok(
            TransferPlan(
                files.mapIndexed { index, file ->
                    val size = if (file.sizeKnown) file.length else null
                    PlannedFile(
                        index,
                        if (single) context.download.fileName ?: file.path else file.path,
                        size,
                        if (size != null) splitEvenly(size, segments) else emptyList(),
                        fresh,
                    )
                },
            ),
        )
    }

    override suspend fun transfer(
        transfer: Transfer,
        io: TransferIo,
    ): StepResult<Unit> =
        try {
            fetch(transfer, io)
        } catch (e: CancellationException) {
            withContext(NonCancellable) { delay(stopDelay) }
            throw e
        }

    private suspend fun fetch(
        transfer: Transfer,
        io: TransferIo,
    ): StepResult<Unit> {
        log("transfer")
        onTransfer?.invoke(io)
        transfers += transfer
        val stop = failure as? FakeFailure.AtTransfer
        var sent = 0L
        var downloaded = transfer.files.sumOf { part -> part.segments.sumOf { it.written } }
        for (part in transfer.files) {
            val index = part.file.index
            val ranges: List<Triple<Segment?, Long, Long>> =
                if (part.segments.isEmpty()) {
                    listOf(Triple(null, 0L, files[index].length))
                } else {
                    part.segments.map { Triple(it, it.watermark, it.end) }
                }
            for ((segment, from, to) in ranges) {
                var at = from
                while (at < to) {
                    if (stop != null && sent >= stop.afterBytes) return stop.failure
                    val n = minOf(chunk.toLong(), to - at, if (stop != null) stop.afterBytes - sent else Long.MAX_VALUE).toInt()
                    io.limiter.acquire(n)
                    delay((n * 1000L / bytesPerSecond).milliseconds)
                    io.meter.record(n.toLong())
                    val written = io.write(part.file.id, at, fakeContent(index, at, n))
                    if (written is StepResult.Failed) return written
                    fetched += Fetched(index, at, at + n)
                    at += n
                    sent += n
                    downloaded += n
                    if (segment != null) io.checkpoint(mapOf(segment.id to at))
                    io.progress(downloaded)
                }
            }
        }
        return stop?.failure ?: StepResult.Ok(Unit)
    }

    /** Every relink request, to check what the queue passed (#572). */
    val relinkRequests = mutableListOf<RelinkRequest>()

    override suspend fun relink(request: RelinkRequest): StepResult<Relinked> {
        log("relink")
        relinkRequests += request
        relinkGate?.await()
        return relinkAnswer ?: super.relink(request)
    }

    override suspend fun finalize(transfer: Transfer): StepResult<FinalizeResult> {
        log("finalize")
        (failure as? FakeFailure.AtFinalize)?.let { return it.failure }
        return StepResult.Ok(FinalizeResult(outputs ?: transfer.files.map { it.file.path }, seeding, consumed, cleanup))
    }

    override fun workFolders(download: Download): List<String> = workFolderNames

    private fun log(call: String) {
        calls += call
        journal?.add(call)
    }
}

/** [count] nearly equal ranges covering [0, size); none for an empty file. */
internal fun splitEvenly(
    size: Long,
    count: Int,
): List<NewSegment> {
    if (size == 0L) return emptyList()
    val n = minOf(count.toLong(), size)
    return (0 until n).map { i -> NewSegment(size * i / n, size * (i + 1) / n) }
}

internal fun fakeByte(
    index: Int,
    offset: Long,
): Byte = ((offset * 31 + index * 7) % 251).toByte()

internal fun fakeContent(
    index: Int,
    offset: Long,
    length: Int,
): ByteArray = ByteArray(length) { fakeByte(index, offset + it) }
