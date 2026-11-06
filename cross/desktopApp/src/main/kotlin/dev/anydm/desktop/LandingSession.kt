package dev.anydm.desktop

import dev.anydm.engine.os.LinkIntake
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The model of the landing page's form, with the scope it runs in. */
class DialogEntry internal constructor(
    val id: Long,
    val model: NewDownloadModel,
    internal val scope: CoroutineScope,
)

/**
 * The landing page's one form: a [model] that links and `.torrent` paths handed over fill in ([open]), and that a start replaces
 * with a blank one ([reset]). Of several links only the first is taken. [make] builds a blank model, which reads the clipboard
 * for a link only when asked to prefill: the first one does, the one after a start does not (the link just started would come back).
 */
class LandingSession(
    private val parent: CoroutineScope,
    private val make: (prefill: Boolean, scope: CoroutineScope) -> NewDownloadModel,
) {
    private var next = 1L
    private val current = MutableStateFlow(build(prefill = true))
    val model: StateFlow<DialogEntry> = current.asStateFlow()

    /** Fills the form with the first of [inputs] that is a link; none, or the link already in the field, changes nothing. */
    fun open(inputs: List<String>) {
        val link = inputs.map { it.trim() }.firstOrNull { LinkIntake.parse(it) != null } ?: return
        val entry = current.value
        if (entry.model.state.value.url
                .trim() == link
        ) {
            return
        }
        entry.model.fill(link)
    }

    /** Takes the `.torrent` files among dropped [paths] into the form; whether there were any. */
    fun dropped(paths: List<String>): Boolean {
        val torrents = droppedTorrents(paths)
        if (torrents.isNotEmpty()) open(torrents)
        return torrents.isNotEmpty()
    }

    /** A blank form for the next link; the one that was is ended. */
    fun reset() {
        val old = current.value
        current.value = build(prefill = false)
        old.scope.cancel()
    }

    private fun build(prefill: Boolean): DialogEntry {
        val job = SupervisorJob(parent.coroutineContext[Job])
        val scope = CoroutineScope(parent.coroutineContext + job)
        return DialogEntry(next++, make(prefill, scope), scope)
    }
}
