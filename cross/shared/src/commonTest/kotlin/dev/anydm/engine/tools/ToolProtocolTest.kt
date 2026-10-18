package dev.anydm.engine.tools

import dev.anydm.engine.core.Checksum
import dev.anydm.engine.core.ChecksumAlgorithm
import dev.anydm.engine.core.Download
import dev.anydm.engine.core.DownloadId
import dev.anydm.engine.core.DownloadKind
import dev.anydm.engine.core.ErrorClass
import dev.anydm.engine.core.File
import dev.anydm.engine.core.FileId
import dev.anydm.engine.core.Mirror
import dev.anydm.engine.core.MirrorId
import dev.anydm.engine.core.hasherFor
import dev.anydm.engine.platform.FakeFileSystem
import dev.anydm.engine.platform.FakeProcessRunner
import dev.anydm.engine.platform.FakeProcessRunner.Scripted
import dev.anydm.engine.platform.FileResult
import dev.anydm.engine.protocol.FakeProbe
import dev.anydm.engine.protocol.FakeProtocol
import dev.anydm.engine.protocol.FinalizeResult
import dev.anydm.engine.protocol.StepResult
import dev.anydm.engine.protocol.Transfer
import dev.anydm.engine.protocol.TransferFile
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

class ToolProtocolTest {
    private val content = ByteArray(5000) { (it * 7).toByte() }
    private val sum = hasherFor(ChecksumAlgorithm.SHA256).also { it.update(content) }.hex()
    private val fs = FakeFileSystem()

    /** Pretends to unpack: makes [created] under the folder and records what it was asked. */
    private inner class FakeUnpacker(
        private val created: List<String>,
        private val result: FileResult<Unit> = FileResult.Ok(Unit),
    ) : Unpacker {
        val unpacked = mutableListOf<Triple<Path, ArchiveKind, Path>>()
        val marked = mutableListOf<Path>()

        override suspend fun unpack(
            archive: Path,
            kind: ArchiveKind,
            into: Path,
        ): FileResult<Unit> {
            unpacked += Triple(archive, kind, into)
            created.forEach { fs.put(Path(into, it), byteArrayOf(1)) }
            return result
        }

        override suspend fun markExecutable(path: Path): FileResult<Unit> {
            marked += path
            return FileResult.Ok(Unit)
        }
    }

    private fun protocol(
        runner: FakeProcessRunner,
        unpacker: Unpacker,
        os: OsFamily = OsFamily.LINUX,
    ) = ToolProtocol(FakeProtocol(), runner, unpacker, fs, os)

    private fun ffmpegFolder() = Path("/cache/tools/ffmpeg/8.1.3")

    private fun transfer(
        tool: String = "ffmpeg",
        fileName: String = "ffmpeg.zip",
        checksum: String? = sum,
    ): Transfer {
        val folder = Path("/cache/tools/$tool/8.1.3")
        val partial = Path(folder, "$fileName.1.anydm")
        val target = Path(folder, fileName)
        fs.put(partial, content)
        val download = Download(DownloadId(1), DownloadKind.TOOL, checksum = checksum?.let { Checksum(ChecksumAlgorithm.SHA256, it) })
        val file = File(FileId(1), DownloadId(1), 0, fileName, content.size.toLong())
        return Transfer(
            download,
            Mirror(MirrorId(1), DownloadId(1), "https://example.com/$fileName", 0),
            FakeProbe(fileName, content.size.toLong()),
            listOf(TransferFile(file, emptyList(), partial, target)),
        )
    }

    private val versionOut =
        Scripted(listOf("/cache/tools/ffmpeg/8.1.3/bin/ffmpeg", "-version"), stdout = listOf("ffmpeg version 8.1.3-static"))
    private val probeOut =
        Scripted(listOf("/cache/tools/ffmpeg/8.1.3/bin/ffprobe", "-version"), stdout = listOf("ffprobe version 8.1.3-static"))

    @Test
    fun aWrongChecksumNeverRunsAnythingFromTheArchive() =
        runTest {
            val runner = FakeProcessRunner(listOf(versionOut))
            val unpacker = FakeUnpacker(listOf("bin/ffmpeg"))

            val failed = protocol(runner, unpacker).finalize(transfer(checksum = "0".repeat(64))).shouldBeInstanceOf<StepResult.Failed>()

            failed.errorClass shouldBe ErrorClass.INTEGRITY
            unpacker.unpacked shouldBe emptyList()
            runner.started shouldBe emptyList()
        }

    @Test
    fun aToolDownloadWithoutAChecksumIsRefused() =
        runTest {
            val unpacker = FakeUnpacker(listOf("bin/ffmpeg"))

            val failed =
                protocol(
                    FakeProcessRunner(emptyList()),
                    unpacker,
                ).finalize(transfer(checksum = null)).shouldBeInstanceOf<StepResult.Failed>()

            failed.message shouldContain "checksum"
            unpacker.unpacked shouldBe emptyList()
        }

    @Test
    fun anArchiveIsUnpackedFromThePartialIntoTheToolFolder() =
        runTest {
            val unpacker = FakeUnpacker(listOf("bin/ffmpeg", "bin/ffprobe"))

            protocol(FakeProcessRunner(listOf(versionOut, probeOut)), unpacker).finalize(transfer()) shouldBe
                StepResult.Ok(FinalizeResult(listOf("ffmpeg.zip")))

            unpacker.unpacked shouldBe listOf(Triple(Path(ffmpegFolder(), "ffmpeg.zip.1.anydm"), ArchiveKind.ZIP, ffmpegFolder()))
        }

    @Test
    fun theKindComesFromTheFileName() =
        runTest {
            val unpacker = FakeUnpacker(listOf("ffmpeg-8.1.3/bin/ffmpeg"))
            val tarOut =
                Scripted(listOf("/cache/tools/ffmpeg/8.1.3/ffmpeg-8.1.3/bin/ffmpeg", "-version"), stdout = listOf("ffmpeg version 8.1.3"))

            protocol(
                FakeProcessRunner(listOf(tarOut)),
                unpacker,
            ).finalize(transfer(fileName = "ffmpeg.tar.xz")).shouldBeInstanceOf<StepResult.Ok<*>>()

            unpacker.unpacked.single().second shouldBe ArchiveKind.TAR_XZ
        }

    @Test
    fun everyExecutableTheArchiveHoldsIsMarkedAndTested() =
        runTest {
            val runner = FakeProcessRunner(listOf(versionOut, probeOut))
            val unpacker = FakeUnpacker(listOf("bin/ffmpeg", "bin/ffprobe", "doc/README"))

            protocol(runner, unpacker).finalize(transfer()).shouldBeInstanceOf<StepResult.Ok<*>>()

            runner.started.map { it.command } shouldBe
                listOf(
                    listOf("/cache/tools/ffmpeg/8.1.3/bin/ffmpeg", "-version"),
                    listOf("/cache/tools/ffmpeg/8.1.3/bin/ffprobe", "-version"),
                )
            unpacker.marked.map { it.toString() } shouldBe
                listOf("/cache/tools/ffmpeg/8.1.3/bin/ffmpeg", "/cache/tools/ffmpeg/8.1.3/bin/ffprobe")
        }

    @Test
    fun anArchiveWithOnlySomeOfTheExecutablesStillPasses() =
        runTest {
            protocol(FakeProcessRunner(listOf(versionOut)), FakeUnpacker(listOf("bin/ffmpeg")))
                .finalize(transfer())
                .shouldBeInstanceOf<StepResult.Ok<*>>()
        }

    @Test
    fun anArchiveWithNoneOfTheExecutablesFails() =
        runTest {
            val failed =
                protocol(
                    FakeProcessRunner(emptyList()),
                    FakeUnpacker(listOf("doc/README")),
                ).finalize(transfer()).shouldBeInstanceOf<StepResult.Failed>()

            failed.errorClass shouldBe null
            failed.message shouldContain "ffmpeg"
        }

    @Test
    fun aFailingSelfTestFailsTheJobWithNoErrorClass() =
        runTest {
            val broken = Scripted(listOf("/cache/tools/ffmpeg/8.1.3/bin/ffmpeg", "-version"), exitCode = 1, stderr = listOf("bad cpu"))

            val failed =
                protocol(
                    FakeProcessRunner(listOf(broken)),
                    FakeUnpacker(listOf("bin/ffmpeg")),
                ).finalize(transfer()).shouldBeInstanceOf<StepResult.Failed>()

            failed.errorClass shouldBe null
            failed.message shouldContain "bad cpu"
        }

    @Test
    fun anUnparsableVersionFailsToo() =
        runTest {
            val odd = Scripted(listOf("/cache/tools/ffmpeg/8.1.3/bin/ffmpeg", "-version"), stdout = listOf("ffmpeg version N-118000-gabc"))

            protocol(
                FakeProcessRunner(listOf(odd)),
                FakeUnpacker(listOf("bin/ffmpeg")),
            ).finalize(transfer()).shouldBeInstanceOf<StepResult.Failed>()
        }

    @Test
    fun aToolThatCannotStartFailsTheJob() =
        runTest {
            val failed =
                protocol(
                    FakeProcessRunner(emptyList()),
                    FakeUnpacker(listOf("bin/ffmpeg")),
                ).finalize(transfer()).shouldBeInstanceOf<StepResult.Failed>()

            failed.message shouldContain "ffmpeg"
        }

    @Test
    fun aToolThatHangsFailsTheJob() =
        runTest {
            val hung =
                Scripted(
                    listOf("/cache/tools/ffmpeg/8.1.3/bin/ffmpeg", "-version"),
                    stdout = listOf("ffmpeg version 8.1.3"),
                    delay = 120.seconds,
                )

            val failed =
                protocol(
                    FakeProcessRunner(listOf(hung)),
                    FakeUnpacker(listOf("bin/ffmpeg")),
                ).finalize(transfer()).shouldBeInstanceOf<StepResult.Failed>()

            failed.message shouldContain "time"
        }

    @Test
    fun anUnpackFailureFailsTheJob() =
        runTest {
            val failed =
                protocol(FakeProcessRunner(emptyList()), FakeUnpacker(emptyList(), FileResult.IoError(Path("/x"), "corrupt")))
                    .finalize(transfer())
                    .shouldBeInstanceOf<StepResult.Failed>()

            failed.message shouldContain "corrupt"
        }

    @Test
    fun windowsLooksForExeFiles() =
        runTest {
            val exe = Scripted(listOf("/cache/tools/ffmpeg/8.1.3/bin/ffmpeg.exe", "-version"), stdout = listOf("ffmpeg version 8.1.3"))

            protocol(FakeProcessRunner(listOf(exe)), FakeUnpacker(listOf("bin/ffmpeg.exe")), OsFamily.WINDOWS)
                .finalize(transfer())
                .shouldBeInstanceOf<StepResult.Ok<*>>()
        }

    @Test
    fun aFolderThatIsNoToolFolderIsRefused() =
        runTest {
            val failed =
                protocol(
                    FakeProcessRunner(emptyList()),
                    FakeUnpacker(emptyList()),
                ).finalize(transfer(tool = "not-a-tool")).shouldBeInstanceOf<StepResult.Failed>()

            failed.message shouldContain "not-a-tool"
        }

    @Test
    fun aBareBinaryIsVerifiedMovedIntoPlaceMarkedAndTested() =
        runTest {
            val yt = Scripted(listOf("/cache/tools/yt-dlp/8.1.3/yt-dlp", "--version"), stdout = listOf("2026.10.01"))
            val unpacker = FakeUnpacker(emptyList())
            val t = transfer(tool = "yt-dlp", fileName = "yt-dlp")

            protocol(FakeProcessRunner(listOf(yt)), unpacker).finalize(t) shouldBe StepResult.Ok(FinalizeResult(listOf("yt-dlp")))

            fs.exists(Path("/cache/tools/yt-dlp/8.1.3/yt-dlp")) shouldBe true
            fs.exists(Path("/cache/tools/yt-dlp/8.1.3/yt-dlp.1.anydm")) shouldBe false
            unpacker.marked.map { it.toString() } shouldBe listOf("/cache/tools/yt-dlp/8.1.3/yt-dlp")
        }

    @Test
    fun aRepeatedFinalizeAfterPlacingTestsThePlacedFileWithoutHashingAgain() =
        runTest {
            val yt = Scripted(listOf("/cache/tools/yt-dlp/8.1.3/yt-dlp", "--version"), stdout = listOf("2026.10.01"))
            val t = transfer(tool = "yt-dlp", fileName = "yt-dlp", checksum = "0".repeat(64))
            // A stopped run already moved the verified file into place; the partial is gone.
            fs.remove(Path("/cache/tools/yt-dlp/8.1.3/yt-dlp.1.anydm"))
            fs.put(Path("/cache/tools/yt-dlp/8.1.3/yt-dlp"), content)

            protocol(FakeProcessRunner(listOf(yt)), FakeUnpacker(emptyList())).finalize(t).shouldBeInstanceOf<StepResult.Ok<*>>()
        }

    @Test
    fun anArchiveRepeatedAfterPlacingIsUnpackedFromThePlacedFile() =
        runTest {
            val unpacker = FakeUnpacker(listOf("bin/ffmpeg"))
            val t = transfer(checksum = "0".repeat(64))
            fs.remove(Path(ffmpegFolder(), "ffmpeg.zip.1.anydm"))
            fs.put(Path(ffmpegFolder(), "ffmpeg.zip"), content)

            protocol(FakeProcessRunner(listOf(versionOut)), unpacker).finalize(t).shouldBeInstanceOf<StepResult.Ok<*>>()

            unpacker.unpacked.single().first shouldBe Path(ffmpegFolder(), "ffmpeg.zip")
        }
}
