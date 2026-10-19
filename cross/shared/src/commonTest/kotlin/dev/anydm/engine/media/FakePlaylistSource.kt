package dev.anydm.engine.media

import dev.anydm.engine.protocol.StepResult

/** A [PlaylistSource] that answers [result] to every listing and keeps the addresses it was asked for. */
internal class FakePlaylistSource(
    var result: StepResult<PlaylistParse>,
) : PlaylistSource {
    val requested = mutableListOf<String>()

    override suspend fun list(url: String): StepResult<PlaylistParse> {
        requested += url
        return result
    }
}
