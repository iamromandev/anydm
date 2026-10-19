package dev.anydm.engine.media

import dev.anydm.engine.protocol.StepResult

/** Lists a playlist or channel page without reading each video (MED-16): what the engine turns into a collection. */
interface PlaylistSource {
    suspend fun list(url: String): StepResult<PlaylistParse>
}
