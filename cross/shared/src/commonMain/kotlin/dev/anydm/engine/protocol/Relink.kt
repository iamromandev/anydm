package dev.anydm.engine.protocol

import dev.anydm.engine.core.Download
import dev.anydm.engine.core.Mirror
import dev.anydm.engine.core.RequestContext

/**
 * A relink to check (FND-12, HTTP-13): may [url] replace [mirror]'s address for [download]? [stored] holds the bytes
 * already fetched, to compare the new link with; [context] is what the job's requests carry besides the URL.
 */
data class RelinkRequest(
    val download: Download,
    val mirror: Mirror,
    val url: String,
    val context: RequestContext? = null,
    val stored: StoredBytes = StoredBytes.None,
) {
    init {
        require(mirror.downloadId == download.id) { "mirror ${mirror.id.value} isn't download ${download.id.value}'s" }
    }
}

/** An accepted relink: [validator] is what the new link says the source is (ProbeResult.validator), or null. */
data class Relinked(
    val validator: String?,
)

/** One stored file of a job: its [written] byte ranges, sorted and disjoint, each below a saved watermark. */
data class StoredFile(
    val index: Int,
    val size: Long?,
    val written: List<LongRange>,
) {
    init {
        require(written.all { !it.isEmpty() && it.first >= 0 }) { "written ranges must be non-empty and start at >= 0" }
        require(written.zipWithNext().all { (a, b) -> a.last < b.first }) { "written ranges must be sorted and disjoint" }
    }
}

/** The bytes a job's earlier runs fetched into its partials, which the runner owns (FND-03); read-only. */
interface StoredBytes {
    val files: List<StoredFile>

    /** [length] bytes of file [index] from [offset], all inside its written ranges; a Failed if they can't be read. */
    suspend fun read(
        index: Int,
        offset: Long,
        length: Int,
    ): StepResult<ByteArray>

    companion object {
        /** Nothing fetched yet. */
        val None: StoredBytes =
            object : StoredBytes {
                override val files: List<StoredFile> = emptyList()

                override suspend fun read(
                    index: Int,
                    offset: Long,
                    length: Int,
                ): StepResult<ByteArray> = throw IllegalArgumentException("nothing is stored")
            }
    }
}
