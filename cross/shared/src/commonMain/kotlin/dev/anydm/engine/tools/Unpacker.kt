package dev.anydm.engine.tools

import dev.anydm.engine.platform.FileResult
import kotlinx.io.files.Path

/** Turns a downloaded tool archive into files on disk (BIN-01). */
interface Unpacker {
    /**
     * Unpacks [archive] into [into], making the folder if needed and overwriting what is there, so a run that stopped
     * half way can be repeated. An entry that would land outside [into] is [FileResult.InvalidName] and nothing is
     * written for it. [ArchiveKind.NONE] has nothing to unpack and is `Ok`.
     */
    suspend fun unpack(
        archive: Path,
        kind: ArchiveKind,
        into: Path,
    ): FileResult<Unit>

    /** Lets the file run as a program; nothing to do on Windows. */
    suspend fun markExecutable(path: Path): FileResult<Unit>
}
