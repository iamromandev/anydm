package dev.anydm.engine.tools

import dev.anydm.engine.core.ErrorClass
import dev.anydm.engine.core.hasherFor
import dev.anydm.engine.core.toStepFailed
import dev.anydm.engine.platform.FileResult
import dev.anydm.engine.platform.FileSystem
import dev.anydm.engine.platform.ProcessOutcome
import dev.anydm.engine.platform.ProcessRunner
import dev.anydm.engine.platform.ProcessSpec
import dev.anydm.engine.protocol.FinalizeResult
import dev.anydm.engine.protocol.Protocol
import dev.anydm.engine.protocol.StepResult
import dev.anydm.engine.protocol.Transfer
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.files.Path
import kotlin.time.Duration.Companion.seconds

private const val CHUNK = 1 shl 20
private val SELF_TEST_LIMIT = 30.seconds

/**
 * The protocol of a TOOL job (BIN-01): the HTTP engine fetches the file, then [finalize] makes it a working tool. The
 * job's save folder is `<cache>/tools/<tool id>/<version>`, its file is one [Artifact], and its checksum is the pinned
 * SHA-256.
 *
 * The engine checks a job's checksum only after `finalize`, and finalize runs the tool, so the checksum is verified here
 * first: nothing from an archive is unpacked or run before it matches. Then the archive is unpacked into the version
 * folder (a bare binary is moved to its final name), every executable it holds is marked and run with `--version`, and
 * a tool that fails that, or prints no version, fails the job. Not every archive holds every executable of its tool
 * (macOS ships ffmpeg and ffprobe apart), so the others are not required here; the manager checks the set is whole.
 */
class ToolProtocol(
    private val http: Protocol,
    private val runner: ProcessRunner,
    private val unpacker: Unpacker,
    private val fileSystem: FileSystem,
    private val os: OsFamily,
) : Protocol by http {
    override suspend fun finalize(transfer: Transfer): StepResult<FinalizeResult> {
        val inner =
            when (val done = http.finalize(transfer)) {
                is StepResult.Failed -> return done
                is StepResult.Ok -> done.value
            }
        val file = transfer.files.singleOrNull() ?: return fail("a tool download has exactly one file")
        val folder = file.target.parent ?: return fail("${file.target} has no folder")
        val tool = Tool.byId(folder.parent?.name.orEmpty()) ?: return fail("$folder is not a tool folder (tools/<tool>/<version>)")
        // A run that stopped after placing the file finalizes again with the partial gone; it was verified before.
        val fresh = fileSystem.size(file.partial) is FileResult.Ok
        if (fresh) verify(transfer, file.partial)?.let { return it }
        val kind = kindOf(file.target.name)
        val executables: List<Path>
        if (kind == ArchiveKind.NONE) {
            if (fresh) {
                val moved = fileSystem.atomicMove(file.partial, file.target, replace = true)
                if (moved is FileResult.Failed) return moved.toStepFailed()
            }
            executables = listOf(file.target)
        } else {
            val unpacked = unpacker.unpack(if (fresh) file.partial else file.target, kind, folder)
            if (unpacked is FileResult.Failed) {
                return StepResult.Failed(
                    null,
                    "can't unpack ${file.target.name}: ${unpacked.detail ?: unpacked::class.simpleName}",
                )
            }
            val names = tool.executables.map { tool.fileName(it, os) }
            val found = findExecutables(fileSystem, folder, names.toSet())
            executables = names.mapNotNull { found[it] }
            if (executables.isEmpty()) return fail("${file.target.name} holds none of: ${tool.executables.joinToString()}")
        }
        for (executable in executables) {
            val marked = unpacker.markExecutable(executable)
            if (marked is FileResult.Failed) return marked.toStepFailed()
            selfTest(tool, executable)?.let { return it }
        }
        return StepResult.Ok(inner)
    }

    private fun fail(message: String) = StepResult.Failed(null, message)

    private fun kindOf(name: String) =
        when {
            name.endsWith(".zip", ignoreCase = true) -> ArchiveKind.ZIP
            name.endsWith(".tar.xz", ignoreCase = true) -> ArchiveKind.TAR_XZ
            else -> ArchiveKind.NONE
        }

    /** Null when the file hashes to what the job expects. */
    private suspend fun verify(
        transfer: Transfer,
        file: Path,
    ): StepResult.Failed? {
        val expected =
            transfer.download.checksum ?: transfer.probe.checksum ?: return fail("a tool download needs a checksum; this one has none")
        val hasher = hasherFor(expected.algorithm)
        var offset = 0L
        while (true) {
            val chunk =
                when (val read = fileSystem.read(file, offset, CHUNK)) {
                    is FileResult.Ok -> read.value
                    is FileResult.Failed -> return read.toStepFailed()
                }
            if (chunk.isEmpty()) break
            hasher.update(chunk)
            offset += chunk.size
        }
        return if (hasher.hex() ==
            expected.hex
        ) {
            null
        } else {
            StepResult.Failed(ErrorClass.INTEGRITY, "${file.name} doesn't match its checksum")
        }
    }

    private suspend fun selfTest(
        tool: Tool,
        executable: Path,
    ): StepResult.Failed? {
        val ran = withTimeoutOrNull(SELF_TEST_LIMIT) { runner.run(ProcessSpec(listOf(executable.toString(), tool.versionArgument))) }
        val result =
            when (ran) {
                null -> return fail("${executable.name} --version timed out after $SELF_TEST_LIMIT")
                is ProcessOutcome.Failed -> return fail("can't run $executable: ${ran.failure}")
                is ProcessOutcome.Ok -> ran.value
            }
        if (result.exitCode != 0) return fail("${executable.name} --version exited ${result.exitCode}: ${result.stderr.take(300)}")
        if (ToolVersion.fromOutput(tool, result.stdout) == null) return fail("${executable.name} --version printed no version")
        return null
    }
}
