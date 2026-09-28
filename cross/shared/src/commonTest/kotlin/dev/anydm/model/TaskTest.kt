package dev.anydm.model

import kotlin.test.Test
import kotlin.test.assertEquals

class TaskTest {
    private fun dto(json: String): TaskDto = AnydmJson.decodeFromString(json)

    @Test
    fun `a status or kind this build doesn't know reads as unknown`() {
        val task = dto("""{"id":"t","status":"teleporting","kind":"hologram"}""").toTask()
        assertEquals(TaskStatus.UNKNOWN, task.status)
        assertEquals(TaskKind.UNKNOWN, task.kind)
    }

    @Test
    fun `the title falls back to the file name, then the link`() {
        assertEquals("a.mp4", dto("""{"id":"t","filename":"a.mp4","source_url":"https://x/a"}""").toTask().title)
        assertEquals("https://x/a", dto("""{"id":"t","source_url":"https://x/a"}""").toTask().title)
    }

    @Test
    fun `fields the API added later are ignored`() {
        assertEquals("t", dto("""{"id":"t","brand_new_field":{"x":1}}""").toTask().id)
    }

    @Test
    fun `a video knows its group`() {
        val video = dto("""{"id":"v","parent_id":"g","position":3}""").toTask()
        assertEquals("g", video.parentId)
        assertEquals(3, video.position)
    }
}
