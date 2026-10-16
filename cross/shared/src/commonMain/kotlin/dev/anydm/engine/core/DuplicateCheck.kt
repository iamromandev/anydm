package dev.anydm.engine.core

/** Asked in preflight whether the job is a duplicate. #539 fills it (FIL-05); it never reports the job itself. */
interface DuplicateCheck {
    suspend fun duplicateOf(
        download: Download,
        mirror: Mirror,
    ): DownloadId?

    data object None : DuplicateCheck {
        override suspend fun duplicateOf(
            download: Download,
            mirror: Mirror,
        ): DownloadId? = null
    }
}
