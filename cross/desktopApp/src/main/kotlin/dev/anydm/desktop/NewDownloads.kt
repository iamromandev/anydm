package dev.anydm.desktop

import dev.anydm.engine.os.LinkIntake
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** One dialog on show or waiting behind the shown one. */
class DialogEntry internal constructor(
    val id: Long,
    val model: NewDownloadModel,
    internal val scope: CoroutineScope,
)

/** What the stack of dialogs shows: [entries] in the order they came, and which of them is on show. */
data class DialogStack(
    val entries: List<DialogEntry> = emptyList(),
    val shown: Int = 0,
) {
    val current: DialogEntry? get() = entries.getOrNull(shown)
}

/**
 * The New download dialogs: one for each link or `.torrent` handed over (several dropped together stack, one is shown, with
 * "1 of 3" to move between them), or one blank dialog. A link that is already open is not opened twice; a blank dialog that has
 * not been typed in takes the first link that arrives. [make] builds the model of a dialog from the link it opens with.
 */
class NewDownloads(
    private val parent: CoroutineScope,
    private val make: (initial: String, scope: CoroutineScope) -> NewDownloadModel,
) {
    private val stack = MutableStateFlow(DialogStack())
    val state: StateFlow<DialogStack> = stack.asStateFlow()
    private var next = 1L

    /** Opens [inputs]: links and `.torrent` paths (anything else is left out), or a blank dialog when there are none. */
    fun open(inputs: List<String>) {
        if (inputs.isEmpty()) {
            if (stack.value.entries.isEmpty()) add("")
            return
        }
        val links = inputs.map { it.trim() }.filter { LinkIntake.parse(it) != null }.distinct()
        if (links.isEmpty()) return
        val have =
            stack.value.entries
                .map {
                    it.model.state.value.url
                        .trim()
                }.toSet()
        val fresh = links.filter { it !in have }
        if (fresh.isEmpty()) return
        val blank =
            stack.value.entries
                .singleOrNull()
                ?.takeIf {
                    it.model.state.value.url
                        .isBlank()
                }
        if (blank != null) {
            blank.model.fill(fresh.first())
            fresh.drop(1).forEach(::add)
        } else {
            fresh.forEach(::add)
        }
    }

    private fun add(initial: String) {
        val job = SupervisorJob(parent.coroutineContext[Job])
        val scope = CoroutineScope(parent.coroutineContext + job)
        val entry = DialogEntry(next++, make(initial, scope), scope)
        stack.update { it.copy(entries = it.entries + entry) }
    }

    fun show(position: Int) = stack.update { if (position in it.entries.indices) it.copy(shown = position) else it }

    /** Closes the dialog [id] (done or cancelled); the next one, if any, comes forward. */
    fun close(id: Long) {
        var closed: DialogEntry? = null
        stack.update { now ->
            closed = now.entries.firstOrNull { it.id == id }
            val at = now.entries.indexOfFirst { it.id == id }
            if (at < 0) {
                now
            } else {
                val rest = now.entries.filterNot { it.id == id }
                DialogStack(rest, if (at < now.shown || now.shown >= rest.size) (now.shown - 1).coerceAtLeast(0) else now.shown)
            }
        }
        closed?.scope?.cancel()
    }
}
