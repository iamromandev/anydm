package dev.anydm.desktop.list

import dev.anydm.model.Task
import dev.anydm.store.ListFilter
import dev.anydm.store.inCategory
import dev.anydm.store.matches

/** What a press on a row means, from its button and modifiers. */
enum class Gesture { CLICK, TOGGLE, EXTEND, CONTEXT }

fun gestureOf(
    meta: Boolean,
    ctrl: Boolean,
    shift: Boolean,
    secondary: Boolean,
    mac: Boolean,
): Gesture =
    when {
        secondary -> Gesture.CONTEXT
        shift -> Gesture.EXTEND
        (mac && meta) || (!mac && ctrl) -> Gesture.TOGGLE
        else -> Gesture.CLICK
    }

/** The rows as drawn, top to bottom: each shown task, then its videos when its group is open. */
fun visibleOrder(
    tasks: List<Task>,
    entries: Map<String, List<Task>>,
    filter: ListFilter,
    categoryFilter: String? = null,
): List<String> =
    tasks
        .filter { filter.matches(it) && inCategory(it, categoryFilter) }
        .flatMap { task -> listOf(task.id) + entries[task.id].orEmpty().map { it.id } }

/**
 * The selected rows by id (spec: "Selection and keys"). [anchor] is where a Shift range starts; [lead] is
 * where the arrows move from.
 */
data class Selection(
    val ids: Set<String> = emptySet(),
    val anchor: String? = null,
    val lead: String? = null,
) {
    fun apply(
        gesture: Gesture,
        id: String,
        order: List<String>,
    ): Selection =
        when (gesture) {
            Gesture.CLICK -> Selection(setOf(id), id, id)
            Gesture.TOGGLE -> Selection(if (id in ids) ids - id else ids + id, id, id)
            Gesture.EXTEND -> anchor?.let { Selection(range(it, id, order), it, id) } ?: Selection(setOf(id), id, id)
            Gesture.CONTEXT -> if (id in ids) this else Selection(setOf(id), id, id)
        }

    fun all(order: List<String>): Selection = Selection(order.toSet(), order.firstOrNull(), order.lastOrNull())

    fun move(
        delta: Int,
        order: List<String>,
        extend: Boolean,
    ): Selection {
        if (order.isEmpty()) return this
        val from = lead?.let(order::indexOf)?.takeIf { it >= 0 }
        val to = if (from == null) (if (delta > 0) 0 else order.lastIndex) else (from + delta).coerceIn(0, order.lastIndex)
        val id = order[to]
        return if (extend && anchor != null) Selection(range(anchor, id, order), anchor, id) else Selection(setOf(id), id, id)
    }

    fun prune(order: List<String>): Selection {
        val visible = order.toSet()
        return Selection(ids intersect visible, anchor?.takeIf { it in visible }, lead?.takeIf { it in visible })
    }

    private fun range(
        from: String,
        to: String,
        order: List<String>,
    ): Set<String> {
        val a = order.indexOf(from)
        val b = order.indexOf(to)
        if (a < 0 || b < 0) return setOf(to)
        return order.subList(minOf(a, b), maxOf(a, b) + 1).toSet()
    }
}
