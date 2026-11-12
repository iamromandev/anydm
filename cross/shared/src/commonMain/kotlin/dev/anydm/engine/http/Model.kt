package dev.anydm.engine.http

fun interface Clock {
    fun nowMs(): Long
}

data class JobSpec(
    val id: String,
    val url: String,
    val folder: String,
    val name: String,
    val done: Long,
    val etag: String?,
    val lastModified: String?,
    /** The size the server last reported, when known: a `.part` file of exactly this size is complete. */
    val total: Long? = null,
)

sealed interface EngineEvent {
    val id: String

    data class Started(
        override val id: String,
        val name: String,
        val total: Long?,
        val etag: String?,
        val lastModified: String?,
        val done: Long,
    ) : EngineEvent

    data class Progress(
        override val id: String,
        val done: Long,
        val total: Long?,
        val bytesPerSec: Long,
    ) : EngineEvent

    data class Finished(
        override val id: String,
        val done: Long,
        /** Set only when the final name is not the one the job was started with (it was taken by then). */
        val name: String? = null,
    ) : EngineEvent

    data class Failed(
        override val id: String,
        val reason: String,
        val done: Long,
    ) : EngineEvent
}

data class ProbeResult(
    val name: String,
    val total: Long?,
    val resumable: Boolean,
)

sealed interface ProbeOutcome {
    data class Ok(
        val r: ProbeResult,
    ) : ProbeOutcome

    data class Failed(
        val reason: String,
    ) : ProbeOutcome
}

object Reasons {
    fun http(code: Int) = "HTTP $code"

    const val NETWORK = "Network error"
    const val CERT = "Network error: certificate"
    const val DISK_FULL = "Disk full"
    const val NOT_WRITABLE = "Can't write to folder"
    const val CHANGED = "File changed on server"
}
