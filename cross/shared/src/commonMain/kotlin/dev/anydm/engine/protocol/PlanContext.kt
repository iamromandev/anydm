package dev.anydm.engine.protocol

import dev.anydm.engine.core.Download

/** What [Protocol.plan] may use besides the probe. */
data class PlanContext(
    /** The job, DOWNLOADING: its file name (the user's or the probe's), limit, profile. */
    val download: Download,
    /** What earlier runs stored, by index, with watermarks; empty on a first run. */
    val stored: List<TransferFile> = emptyList(),
)
