package dev.anydm.engine.core

/** Where a job goes after a step fails; #553 replaces [Fail] with one that decides by error class. */
interface FailurePolicy {
    /**
     * The status to move to. The move must be legal (FND-02), or the runner throws. Returning the status the job is in
     * keeps it there, which only makes sense while it is PENDING (a transient probe failure that #553 will retry): a
     * job that was already QUEUED, DOWNLOADING or MUXING has a slot and a state to leave, so the runner throws if the
     * policy keeps it there.
     */
    suspend fun statusAfter(
        download: Download,
        failure: StepFailure,
    ): DownloadStatus

    data object Fail : FailurePolicy {
        override suspend fun statusAfter(
            download: Download,
            failure: StepFailure,
        ) = DownloadStatus.FAILED
    }
}
