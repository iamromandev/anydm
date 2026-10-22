package dev.anydm.engine.os

import dev.anydm.engine.Engine

/**
 * Program arguments that are links (OSI-07, OSI-08): the ones the app was started with, and the ones a second launch hands
 * to the running app. Each goes through the engine's intake in the order given. An argument that is not a link (a flag, a
 * stray word) is [AddLinkResult.NotALink] and nothing else happens; the others still go in.
 */
class StartupArgs(
    private val engine: Engine,
) {
    suspend fun handle(
        arguments: List<String>,
        entry: EntryPoint,
    ): List<AddLinkResult> = arguments.map { engine.addLink(it, entry) }
}
