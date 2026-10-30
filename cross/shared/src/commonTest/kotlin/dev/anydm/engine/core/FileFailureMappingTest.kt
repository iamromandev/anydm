package dev.anydm.engine.core

import dev.anydm.engine.platform.FileResult
import io.kotest.matchers.collections.shouldBeEmpty
import kotlinx.io.files.Path
import kotlin.test.Test

class FileFailureMappingTest {
    @Test
    fun onlyAFullDiskAndABusyFileHaveAClass() {
        val p = Path(stepRoot, "x.zip")
        // Written by hand, independently of the mapping under test.
        val expected: List<Pair<FileResult.Failed, ErrorClass?>> =
            listOf(
                FileResult.DiskFull(p) to ErrorClass.DISK_FULL,
                FileResult.Busy(p) to ErrorClass.TRANSIENT,
                FileResult.AccessDenied(p) to null,
                FileResult.NotFound(p) to null,
                FileResult.AlreadyExists(p) to null,
                FileResult.InvalidName(p) to null,
                FileResult.CrossVolume(p, Path(stepRoot, "y.zip")) to null,
                FileResult.TrashUnavailable(p) to null,
                FileResult.IoError(p) to null,
            )
        val mismatches =
            buildList {
                for ((failed, errorClass) in expected) {
                    val mapped = failed.toStepFailed()
                    if (mapped.errorClass != errorClass) add("$failed: expected $errorClass, got ${mapped.errorClass}")
                    if (p.toString() !in mapped.message) add("$failed: message lacks the path: ${mapped.message}")
                }
                val withDetail = FileResult.IoError(p, "boom").toStepFailed()
                if (!withDetail.message.endsWith(": boom")) add("detail is lost: ${withDetail.message}")
            }
        mismatches.shouldBeEmpty()
    }
}
