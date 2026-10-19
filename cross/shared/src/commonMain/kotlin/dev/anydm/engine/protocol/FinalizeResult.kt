package dev.anydm.engine.protocol

/**
 * [outputs]: paths relative to the save folder whose partial holds a finished file; the runner renames each into
 * place, refusing to replace a file that is already there (FIL-04 and FIL-07 are #539's). A partial is
 * `<output>.<download id>.anydm` beside it, where the id is `Transfer.download.id`: for a planned file that is
 * TransferFile.partial, and a protocol that writes an output of its own (a merged media file) makes the partial
 * itself. The outputs get the same checks as planned paths: inside the folder, none ending in `.anydm`, none twice
 * (also by case), none inside another. [seeding]: the job becomes SEEDING, not COMPLETED.
 */
data class FinalizeResult(
    val outputs: List<String>,
    val seeding: Boolean = false,
    /**
     * Planned files (by path) that finalize merged into [outputs] and so are no longer needed. Once the job is complete
     * the runner deletes them and forgets their rows; an output with no row of its own gets one.
     */
    val consumed: List<String> = emptyList(),
    /**
     * Folders (paths relative to the save folder) that finalize made for its own use: fragments, a staging folder. Once
     * the job is complete the runner removes them with everything in them; a path that leaves the save folder is ignored.
     */
    val cleanup: List<String> = emptyList(),
)
