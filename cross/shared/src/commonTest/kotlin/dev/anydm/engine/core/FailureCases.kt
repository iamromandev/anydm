package dev.anydm.engine.core

import dev.anydm.engine.core.DownloadStatus.DOWNLOADING
import dev.anydm.engine.core.DownloadStatus.FAILED
import dev.anydm.engine.core.DownloadStatus.MUXING
import dev.anydm.engine.core.DownloadStatus.QUEUED
import dev.anydm.engine.platform.FakeFileSystem.Op
import dev.anydm.engine.platform.FileResult
import dev.anydm.engine.protocol.FakeFailure
import dev.anydm.engine.protocol.FakeFile
import dev.anydm.engine.protocol.StepResult
import dev.anydm.engine.protocol.fakeContent
import kotlinx.io.files.Path

private val toFailed = listOf(FAILED)
private val inTransfer = listOf(QUEUED, DOWNLOADING, FAILED)
private val inPost = listOf(QUEUED, DOWNLOADING, MUXING, FAILED)
private val noCalls = emptyList<String>()
private val probeOnly = listOf("probe")
private val upToPlan = listOf("probe", "plan")
private val upToTransfer = listOf("probe", "plan", "transfer")
private val allFour = listOf("probe", "plan", "transfer", "finalize")

/**
 * A way a job can fail: where, with which class, the statuses it moved through, the protocol calls made, and whether
 * the partial `file.zip.1.anydm` is left behind for a resume ([partialKept]).
 */
internal class FailureCase(
    val name: String,
    val step: Step,
    val errorClass: ErrorClass?,
    val moves: List<DownloadStatus>,
    val calls: List<String>,
    val partialKept: Boolean = false,
    val download: Download = aPendingDownload(),
    val setUp: StepHarness.() -> Unit = {},
)

/** Segments whose watermark vouches for bytes the partial doesn't hold, as messages; none is the only right answer. */
internal suspend fun StepHarness.watermarksNotBackedByBytes(): List<String> =
    buildList {
        for (row in store.files(DownloadId(1))) {
            val bytes = fs.read(partialPath(targetPath(stepRoot, row.path), DownloadId(1)))
            for (segment in store.segments(row.id)) {
                if (segment.written == 0L) continue
                val range = segment.start.toInt() until segment.watermark.toInt()
                val held = bytes?.let { if (it.size >= segment.watermark) it.copyOfRange(range.first, range.last + 1) else null }
                if (held == null || !held.contentEquals(fakeContent(row.index, segment.start, range.count()))) {
                    add("a watermark of ${row.path} vouches for bytes that aren't in its partial")
                }
            }
        }
    }

internal val failureCases =
    listOf(
        FailureCase("no protocol", Step.PREFLIGHT, null, toFailed, noCalls, download = aPendingDownload(kind = DownloadKind.FTP)),
        FailureCase("relative folder", Step.PREFLIGHT, null, toFailed, noCalls, download = aPendingDownload(saveFolder = "downloads")),
        FailureCase("folder not writable", Step.PREFLIGHT, null, toFailed, noCalls) {
            fs.faults[Op.PREPARE_FOLDER] = { FileResult.AccessDenied(it) }
        },
        FailureCase("low free space", Step.PREFLIGHT, ErrorClass.DISK_FULL, toFailed, noCalls, download = aPendingDownload(size = 65536)) {
            fs.free = 1_000
        },
        FailureCase(
            "free space unreadable at preflight",
            Step.PREFLIGHT,
            null,
            toFailed,
            noCalls,
            download = aPendingDownload(size = 65536),
        ) {
            fs.faults[Op.FREE_SPACE] = { FileResult.IoError(it, "boom") }
        },
        FailureCase("duplicate", Step.PREFLIGHT, null, toFailed, noCalls) {
            duplicate = DownloadId(7)
        },
        FailureCase("probe", Step.PROBE, ErrorClass.GONE, toFailed, probeOnly) {
            protocol.failure = FakeFailure.AtProbe(StepResult.Failed(ErrorClass.GONE, "404 Not Found"))
        },
        FailureCase("no room for the probed size", Step.PROBE, ErrorClass.DISK_FULL, toFailed, probeOnly) {
            fs.free = 1_000 // the size was unknown when the job was added
        },
        FailureCase("free space unreadable after the probe", Step.PROBE, null, toFailed, probeOnly) {
            fs.faults[Op.FREE_SPACE] = { FileResult.IoError(it, "boom") }
        },
        FailureCase("plan", Step.TRANSFER, ErrorClass.FORMAT_WITHHELD, inTransfer, upToPlan) {
            protocol.failure = FakeFailure.AtPlan(StepResult.Failed(ErrorClass.FORMAT_WITHHELD, "no format"))
        },
        FailureCase("path outside folder", Step.TRANSFER, null, inTransfer, upToPlan) {
            protocol.files = listOf(FakeFile("a.zip", 10), FakeFile("../b.zip", 10))
        },
        FailureCase("path that is a partial's name", Step.TRANSFER, null, inTransfer, upToPlan) {
            protocol.files = listOf(FakeFile("x", 10), FakeFile("x.anydm", 10))
        },
        FailureCase("single path that is a partial's name", Step.TRANSFER, null, inTransfer, upToPlan) {
            protocol.files = listOf(FakeFile("x.anydm", 10))
        },
        FailureCase("paths equal but for case", Step.TRANSFER, null, inTransfer, upToPlan) {
            protocol.files = listOf(FakeFile("A.bin", 10), FakeFile("a.bin", 10))
        },
        FailureCase("a file inside a file", Step.TRANSFER, null, inTransfer, upToPlan) {
            protocol.files = listOf(FakeFile("x", 10), FakeFile("x/y", 10))
        },
        FailureCase("subfolder can't be made", Step.TRANSFER, null, inTransfer, upToPlan) {
            protocol.files = listOf(FakeFile("Album/1.flac", 10), FakeFile("Album/2.flac", 10))
            fs.faults[Op.PREPARE_FOLDER] = { if (it.name == "Album") FileResult.AccessDenied(it) else null }
        },
        FailureCase("stale partial can't be deleted", Step.TRANSFER, null, inTransfer, upToPlan) {
            fs.faults[Op.DELETE] = { FileResult.AccessDenied(it) }
        },
        FailureCase("partial can't be made", Step.TRANSFER, ErrorClass.DISK_FULL, inTransfer, upToPlan) {
            fs.faults[Op.PREALLOCATE] = { FileResult.DiskFull(it) }
        },
        FailureCase("partial can't be opened", Step.TRANSFER, null, inTransfer, upToPlan, partialKept = true) {
            fs.faults[Op.OPEN_WRITER] = { FileResult.AccessDenied(it) }
        },
        FailureCase("disk fills while writing", Step.TRANSFER, ErrorClass.DISK_FULL, inTransfer, upToTransfer, partialKept = true) {
            var writes = 0
            fs.faults[Op.WRITE] = { if (++writes > 3) FileResult.DiskFull(it) else null }
        },
        FailureCase("transfer fails midway", Step.TRANSFER, ErrorClass.TRANSIENT, inTransfer, upToTransfer, partialKept = true) {
            protocol.failure = FakeFailure.AtTransfer(10_000, StepResult.Failed(ErrorClass.TRANSIENT, "reset"))
        },
        FailureCase("partial can't be synced", Step.TRANSFER, null, inTransfer, upToTransfer, partialKept = true) {
            fs.faults[Op.SYNC] = { FileResult.IoError(it, "boom") }
        },
        FailureCase("finalize", Step.POST_PROCESS, ErrorClass.INTEGRITY, inPost, allFour, partialKept = true) {
            protocol.failure = FakeFailure.AtFinalize(StepResult.Failed(ErrorClass.INTEGRITY, "sha256 mismatch"))
        },
        FailureCase("output outside folder", Step.POST_PROCESS, null, inPost, allFour, partialKept = true) {
            protocol.outputs = listOf("../x")
        },
        FailureCase("output that is a partial's name", Step.POST_PROCESS, null, inPost, allFour, partialKept = true) {
            protocol.outputs = listOf("file.zip.anydm")
        },
        FailureCase("output nobody planned", Step.POST_PROCESS, null, inPost, allFour, partialKept = true) {
            protocol.outputs = listOf("other.zip")
        },
        FailureCase("rename busy", Step.POST_PROCESS, ErrorClass.TRANSIENT, inPost, allFour, partialKept = true) {
            fs.faults[Op.ATOMIC_MOVE] = { FileResult.Busy(it) }
        },
        FailureCase("rename refused", Step.POST_PROCESS, null, inPost, allFour, partialKept = true) {
            fs.faults[Op.ATOMIC_MOVE] = { FileResult.AccessDenied(it) }
        },
    )
