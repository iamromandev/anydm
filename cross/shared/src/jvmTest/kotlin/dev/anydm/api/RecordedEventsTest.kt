package dev.anydm.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RecordedEventsTest {
    @Test
    fun `the recorded stream opens with a snapshot and parses throughout`() {
        val text = requireNotNull(javaClass.getResource("/fixtures/events.txt")).readText()
        val frames = parseSse(text)
        val events = frames.mapNotNull(::toServerEvent)
        // Every frame the API sent is one this build knows.
        assertEquals(frames.size, events.size)
        assertIs<ServerEvent.Snapshot>(events.first())
        assertTrue(events.any { it is ServerEvent.TaskChanged })
        assertTrue(events.any { it is ServerEvent.CollectionChanged })
        // The torrent's frames carry its live numbers.
        assertTrue(events.filterIsInstance<ServerEvent.Progress>().any { (it.progress.live?.peers ?: 0) > 0 })
    }
}
