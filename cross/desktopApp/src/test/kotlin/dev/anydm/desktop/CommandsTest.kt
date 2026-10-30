package dev.anydm.desktop

import dev.anydm.engine.FileSnapshot
import dev.anydm.engine.core.Attempt
import dev.anydm.engine.core.AttemptId
import dev.anydm.engine.core.DownloadId
import dev.anydm.engine.core.DownloadStatus
import dev.anydm.engine.core.DownloadStatus.CANCELLED
import dev.anydm.engine.core.DownloadStatus.COMPLETED
import dev.anydm.engine.core.DownloadStatus.DOWNLOADING
import dev.anydm.engine.core.DownloadStatus.FAILED
import dev.anydm.engine.core.DownloadStatus.MUXING
import dev.anydm.engine.core.DownloadStatus.PAUSED
import dev.anydm.engine.core.DownloadStatus.PENDING
import dev.anydm.engine.core.DownloadStatus.QUEUED
import dev.anydm.engine.core.DownloadStatus.SEEDING
import dev.anydm.engine.core.ErrorClass
import dev.anydm.engine.core.File
import dev.anydm.engine.core.FileId
import dev.anydm.engine.core.Mirror
import dev.anydm.engine.core.MirrorId
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class CommandsTest {
    private fun allowed(command: JobCommand) = DownloadStatus.entries.filter { command.appliesTo(snapshot(1, it)) }.toSet()

    @Test
    fun `each command applies to the statuses it can move from`() {
        assertEquals(setOf(PENDING, QUEUED, DOWNLOADING), allowed(JobCommand.PAUSE))
        assertEquals(setOf(PAUSED), allowed(JobCommand.RESUME))
        assertEquals(setOf(PENDING, QUEUED, DOWNLOADING, PAUSED, MUXING), allowed(JobCommand.CANCEL))
        assertEquals(setOf(FAILED), allowed(JobCommand.RETRY))
        assertEquals(allowed(JobCommand.CANCEL), allowed(JobCommand.RAISE))
        assertEquals(setOf(SEEDING), allowed(JobCommand.STOP_SEEDING))
        assertEquals(DownloadStatus.entries.toSet(), allowed(JobCommand.REMOVE))
        assertEquals(emptySet(), allowed(JobCommand.OPEN)) // nothing placed
        assertEquals(emptySet(), allowed(JobCommand.RELINK)) // no failure, no mirror
        assertEquals(emptySet(), allowed(JobCommand.DOWNLOAD_ANYWAY))
        assertEquals(true, CANCELLED !in allowed(JobCommand.CANCEL))
    }

    private fun failed(errorClass: ErrorClass?) =
        snapshot(
            1,
            FAILED,
            attempts =
                listOf(
                    Attempt(AttemptId(1), DownloadId(1), Instant.fromEpochSeconds(0), MirrorId(5), errorClass = errorClass, message = "x"),
                ),
            mirrors = listOf(Mirror(MirrorId(5), DownloadId(1), "https://a.example/f", 0)),
        )

    @Test
    fun `a gone or refused link can be relinked`() {
        assertEquals(true, JobCommand.RELINK.appliesTo(failed(ErrorClass.GONE)))
        assertEquals(true, JobCommand.RELINK.appliesTo(failed(ErrorClass.AUTH)))
        assertEquals(false, JobCommand.RELINK.appliesTo(failed(ErrorClass.TRANSIENT)))
    }

    @Test
    fun `only a duplicate failure can be downloaded anyway`() {
        assertEquals(true, JobCommand.DOWNLOAD_ANYWAY.appliesTo(failed(ErrorClass.DUPLICATE)))
        assertEquals(false, JobCommand.DOWNLOAD_ANYWAY.appliesTo(failed(ErrorClass.GONE)))
    }

    @Test
    fun `a finished job with a placed file can be opened`() {
        val file = FileSnapshot(File(FileId(1), DownloadId(1), 0, "a.iso", placedPath = "a (1).iso"), emptyList())
        val done = snapshot(1, COMPLETED, files = listOf(file), saveFolder = "/tmp/dl")
        assertEquals(Path.of("/tmp/dl/a (1).iso"), placedFile(done))
        assertEquals(true, JobCommand.OPEN.appliesTo(done))
        assertEquals(true, JobCommand.REVEAL.appliesTo(done))
        assertEquals(true, JobCommand.COPY_PATH.appliesTo(done))
        assertEquals(false, JobCommand.OPEN.appliesTo(snapshot(1, COMPLETED, saveFolder = "/tmp/dl")))
    }
}
