package dev.anydm.engine.e2e

import dev.anydm.engine.DownloadSnapshot
import dev.anydm.engine.Engine
import dev.anydm.engine.core.DownloadStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration

/** Runs [block]; an AssertionError from it is rethrown with [clue] in front (Kotest's withClue passes those through). */
internal inline fun <T> withCaseClue(
    clue: String,
    block: () -> T,
): T =
    try {
        block()
    } catch (e: AssertionError) {
        throw AssertionError("$clue: ${e.message}", e)
    }

/** The engine's one job once it finished, within [timeout]: COMPLETED is returned, FAILED fails with its reason. */
internal suspend fun Engine.awaitFinished(timeout: Duration): DownloadSnapshot {
    val end =
        withTimeout(timeout) {
            downloads
                .map { it.singleOrNull() }
                .first { it != null && it.download.status in setOf(DownloadStatus.COMPLETED, DownloadStatus.FAILED) }!!
        }
    if (end.download.status == DownloadStatus.FAILED) throw AssertionError("download failed: ${end.lastFailure?.message}")
    return end
}
