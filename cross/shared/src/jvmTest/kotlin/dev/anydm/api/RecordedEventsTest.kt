package dev.anydm.api

import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RecordedEventsTest {
    @Test
    fun `the recorded stream opens with a snapshot and parses throughout`() {
        val text = requireNotNull(javaClass.getResource("/fixtures/events.txt")).readText()
        val events = parseSse(text).mapNotNull(::toServerEvent)
        assertTrue(events.isNotEmpty())
        assertIs<ServerEvent.Snapshot>(events.first())
    }
}
