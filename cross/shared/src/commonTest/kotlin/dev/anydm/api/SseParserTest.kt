package dev.anydm.api

import kotlin.test.Test
import kotlin.test.assertEquals

class SseParserTest {
    @Test
    fun `a named frame ends at its blank line`() {
        assertEquals(listOf(SseFrame("task", """{"id":"a"}""")), parseSse("event: task\ndata: {\"id\":\"a\"}\n\n"))
    }

    @Test
    fun `several data lines join with newlines, and CRLF reads like LF`() {
        assertEquals(listOf(SseFrame("message", "a\nb")), parseSse("data: a\r\ndata: b\r\n\r\n"))
    }

    @Test
    fun `comments and pings are skipped`() {
        assertEquals(emptyList(), parseSse(": ping - 2026-09-28\n\n"))
    }

    @Test
    fun `a frame fed a line at a time comes out whole, only at its end`() {
        val parser = SseParser()
        assertEquals(null, parser.feed("event: progress"))
        assertEquals(null, parser.feed("""data: {"id":"a","progress":5}"""))
        assertEquals(SseFrame("progress", """{"id":"a","progress":5}"""), parser.feed(""))
        assertEquals(null, parser.feed(""))
    }

    @Test
    fun `an unfinished last frame is not emitted`() {
        assertEquals(emptyList(), parseSse("event: task\ndata: {}\n"))
    }
}
