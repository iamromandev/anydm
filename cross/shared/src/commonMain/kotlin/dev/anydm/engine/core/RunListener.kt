package dev.anydm.engine.core

/** #552 persists each move (status, saveFolder, fileName, size) through its single command path; #555 makes events of both. */
interface RunListener {
    /** The job moved to [download]'s status, before the step that runs in it starts. */
    suspend fun moved(download: Download) {}

    /** Bytes in the partials so far, of [total] when known. Called often. */
    fun progress(
        downloaded: Long,
        total: Long?,
    ) {}

    data object None : RunListener
}
