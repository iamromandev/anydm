package dev.anydm.engine.media

import dev.anydm.engine.protocol.StepResult

/** Lists a playlist or channel page without reading each video (MED-16): what the engine turns into a collection. */
interface PlaylistSource {
    /** The listing of [url], read no further than its [end]th entry (the selection needs no more). */
    suspend fun list(
        url: String,
        end: Int = LISTING_CAP + 1,
    ): StepResult<PlaylistParse>
}
