package dev.anydm.desktop.ui

import dev.anydm.model.TaskDto
import dev.anydm.model.toTask
import dev.anydm.store.RemovePrompt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BulkTest {
    private fun task(
        id: String,
        status: String,
    ) = TaskDto(id = id, mediaKind = "file", status = status, title = id).toTask()

    @Test
    fun `Space pauses whatever runs in a mixed selection, else resumes the stopped`() {
        val mixed = listOf(task("a", "downloading"), task("b", "paused"), task("c", "seeding"), task("d", "completed"))
        val pause = bulkIntent(mixed)
        assertEquals(CardAction.PAUSE, pause?.action)
        assertEquals(listOf("a", "c"), pause?.targets?.map { it.id })

        val stopped = listOf(task("b", "paused"), task("f", "failed"), task("d", "completed"))
        val resume = bulkIntent(stopped)
        assertEquals(CardAction.RESUME, resume?.action)
        assertEquals(listOf("b", "f"), resume?.targets?.map { it.id })

        assertNull(bulkIntent(listOf(task("d", "completed"))))
        assertNull(bulkIntent(emptyList()))
    }

    @Test
    fun `removing several asks once, offering to keep files only when every one can`() {
        val keep = RemovePrompt("Remove from the list?", "…", true, "Remove")
        val stop = RemovePrompt("Stop and remove?", "…", false, "Stop and remove")
        assertEquals(keep, removeManyPrompt(listOf(keep)))
        val many = removeManyPrompt(listOf(keep, keep, keep))
        assertEquals("Remove 3 downloads?", many.heading)
        assertEquals(true, many.canKeepFiles)
        assertEquals("Remove", many.confirmLabel)
        assertEquals(false, removeManyPrompt(listOf(keep, stop)).canKeepFiles)
    }

    @Test
    fun `the dialog names up to three, then counts the rest`() {
        assertEquals("clip", removeTitle(listOf("clip")))
        assertEquals("a, b, c", removeTitle(listOf("a", "b", "c")))
        assertEquals("a, b, c and 2 more", removeTitle(listOf("a", "b", "c", "d", "e")))
    }
}
