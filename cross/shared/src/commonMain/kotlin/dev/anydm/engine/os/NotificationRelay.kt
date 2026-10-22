package dev.anydm.engine.os

import dev.anydm.engine.EngineEvent
import dev.anydm.engine.core.DownloadId
import dev.anydm.engine.core.DownloadKind
import dev.anydm.engine.log.Log
import dev.anydm.engine.net.AllowanceAction
import dev.anydm.engine.platform.Notifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * Turns the engine's events into notifications (OSI-09): a download finished, a download failed for good, a network's data
 * allowance reached. Each is one notification, and only these. What is shown is a title and a short line made of a file
 * name or a network's name and, for a failure, the engine's own reason; web addresses are cut out of it, so a login or a
 * query string in a link never reaches the screen. [enabled] is asked for each event, so the setting takes effect at once.
 * A notifier that fails is logged and the next event is still told.
 */
class NotificationRelay(
    private val events: Flow<EngineEvent>,
    private val describe: (DownloadId) -> Described?,
    private val enabled: suspend () -> Boolean,
    private val notifier: Notifier,
    private val log: Log = Log.None,
) {
    /** What the engine knows of a download, for the line under the title. */
    data class Described(
        val name: String,
        val kind: DownloadKind,
    )

    fun start(scope: CoroutineScope): Job = scope.launch { events.collect { handle(it) } }

    private suspend fun handle(event: EngineEvent) {
        val told =
            when (event) {
                is EngineEvent.Completed -> {
                    val known = describe(event.id)
                    if (known?.kind ==
                        DownloadKind.TOOL
                    ) {
                        null
                    } else {
                        "Download finished" to
                            clean(event.outputs.firstOrNull()?.name ?: known?.name ?: A_DOWNLOAD, NAME_MAX)
                    }
                }

                is EngineEvent.Failed -> {
                    val known = describe(event.id)
                    if (known?.kind == DownloadKind.TOOL) {
                        null
                    } else {
                        "Download failed" to "${clean(known?.name ?: A_DOWNLOAD, NAME_MAX)}: ${clean(event.message, REASON_MAX)}"
                    }
                }

                is EngineEvent.AllowanceReached -> {
                    val what = if (event.action == AllowanceAction.PAUSE) "downloads paused" else "downloads slowed"
                    "Data allowance reached" to "${clean(event.network, NAME_MAX)}: $what"
                }

                else -> {
                    null
                }
            } ?: return
        if (!enabled()) return
        try {
            notifier.notify(told.first, told.second)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("notify") { "a notification could not be shown: ${e.message}" }
        }
    }

    // One line, no control characters, no web addresses, and not long.
    private fun clean(
        text: String,
        max: Int,
    ): String {
        val flat = text.map { if (unsafe(it)) ' ' else it }.joinToString("")
        val noLinks = flat.replace(ADDRESS, "a link").replace(Regex("\\s+"), " ").trim()
        return if (noLinks.length <= max) noLinks else noLinks.take(max - 1) + "…"
    }

    // Control characters, and the ones that reorder what is shown (a name read right to left).
    private fun unsafe(c: Char): Boolean =
        c < ' ' || c in '\u007f'..'\u009f' || c in '\u200e'..'\u200f' || c in '\u202a'..'\u202e' || c in '\u2066'..'\u2069'

    private companion object {
        const val A_DOWNLOAD = "A download"
        const val NAME_MAX = 60
        const val REASON_MAX = 80
        val ADDRESS = Regex("[A-Za-z][A-Za-z0-9+.-]*://\\S+")
    }
}
