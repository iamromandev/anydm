package dev.anydm.engine.core

import kotlin.time.Instant

/**
 * One try at fetching a [Download], optionally against one [Mirror]. A failed try has an
 * [errorClass] and a [message]; what each class makes the engine do is decided elsewhere (#553).
 *
 * The times are wall-clock, so [endedAt] may precede [startedAt] if the clock was set back.
 */
data class Attempt(
    val id: AttemptId,
    val downloadId: DownloadId,
    val startedAt: Instant,
    val mirrorId: MirrorId? = null,
    /** Null while the try is still running. */
    val endedAt: Instant? = null,
    val errorClass: ErrorClass? = null,
    val message: String? = null,
)
