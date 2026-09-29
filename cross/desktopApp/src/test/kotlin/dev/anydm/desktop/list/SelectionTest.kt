package dev.anydm.desktop.list

import dev.anydm.model.TaskDto
import dev.anydm.model.toTask
import dev.anydm.store.ListFilter
import kotlin.test.Test
import kotlin.test.assertEquals

class SelectionTest {
    private val order = listOf("a", "b", "c", "d", "e")

    @Test
    fun `a click selects one, a modifier-click toggles, a shift-click takes the range from the anchor`() {
        val one = Selection().apply(Gesture.CLICK, "b", order)
        assertEquals(setOf("b"), one.ids)
        val two = one.apply(Gesture.TOGGLE, "d", order)
        assertEquals(setOf("b", "d"), two.ids)
        assertEquals(setOf("b"), two.apply(Gesture.TOGGLE, "d", order).ids)
        val range = one.apply(Gesture.EXTEND, "e", order)
        assertEquals(setOf("b", "c", "d", "e"), range.ids)
        assertEquals("b", range.anchor)
        assertEquals(setOf("a", "b"), range.apply(Gesture.EXTEND, "a", order).ids)
        assertEquals(setOf("c"), Selection().apply(Gesture.EXTEND, "c", order).ids)
    }

    @Test
    fun `a right-click keeps a selection it lands in, and replaces one it doesn't`() {
        val two = Selection(setOf("a", "b"), anchor = "a", lead = "b")
        assertEquals(setOf("a", "b"), two.apply(Gesture.CONTEXT, "b", order).ids)
        assertEquals(setOf("d"), two.apply(Gesture.CONTEXT, "d", order).ids)
    }

    @Test
    fun `arrows move from the lead, shift-arrows extend, and the ends hold`() {
        assertEquals(setOf("a"), Selection().move(1, order, extend = false).ids)
        assertEquals(setOf("e"), Selection().move(-1, order, extend = false).ids)
        val c = Selection().apply(Gesture.CLICK, "c", order)
        assertEquals(setOf("d"), c.move(1, order, extend = false).ids)
        assertEquals(setOf("c", "d", "e"), c.move(1, order, true).move(1, order, true).ids)
        assertEquals(setOf("e"), Selection().apply(Gesture.CLICK, "e", order).move(1, order, false).ids)
        assertEquals(setOf("a", "b", "c", "d", "e"), Selection().all(order).ids)
    }

    @Test
    fun `what leaves the view leaves the selection`() {
        val picked = Selection(setOf("a", "x"), anchor = "x", lead = "a")
        val pruned = picked.prune(order)
        assertEquals(setOf("a"), pruned.ids)
        assertEquals(null, pruned.anchor)
        assertEquals("a", pruned.lead)
    }

    @Test
    fun `gestures read the OS's modifier, and the order includes an open group's videos`() {
        assertEquals(Gesture.TOGGLE, gestureOf(meta = true, ctrl = false, shift = false, secondary = false, mac = true))
        assertEquals(Gesture.CLICK, gestureOf(meta = false, ctrl = true, shift = false, secondary = false, mac = true))
        assertEquals(Gesture.TOGGLE, gestureOf(meta = false, ctrl = true, shift = false, secondary = false, mac = false))
        assertEquals(Gesture.EXTEND, gestureOf(meta = false, ctrl = false, shift = true, secondary = false, mac = true))
        assertEquals(Gesture.CONTEXT, gestureOf(meta = false, ctrl = false, shift = true, secondary = true, mac = true))

        val g = TaskDto(id = "g", kind = "playlist", status = "downloading", title = "g").toTask()
        val f = TaskDto(id = "f", kind = "file", status = "complete", title = "f").toTask()
        val v = TaskDto(id = "v", kind = "video", status = "complete", title = "v", parentId = "g").toTask()
        assertEquals(listOf("g", "v", "f"), visibleOrder(listOf(g, f), mapOf("g" to listOf(v)), ListFilter.ALL))
        assertEquals(listOf("f"), visibleOrder(listOf(g, f), mapOf("g" to listOf(v)), ListFilter.COMPLETED))
    }
}
