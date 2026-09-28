package dev.anydm.desktop.chrome

import dev.anydm.model.SummaryDto
import dev.anydm.store.Connection
import dev.anydm.store.ListFilter
import kotlin.test.Test
import kotlin.test.assertEquals

class SourceListTest {
    @Test
    fun `the sidebar lists the four filters with the server's counts`() {
        val items = sourceItems(SummaryDto(all = 12, downloading = 3, seeding = 1, completed = 8))
        assertEquals(listOf("All" to 12, "Active" to 3, "Seeding" to 1, "Completed" to 8), items.map { it.label to it.count })
        assertEquals(ListFilter.ACTIVE, items[1].filter)
        assertEquals(listOf(null, null, null, null), sourceItems(null).map { it.count })
    }

    @Test
    fun `the server dot is green live, amber on the way, red offline`() {
        assertEquals(Dot.OK, connectionDot(Connection.Live))
        assertEquals(Dot.WARN, connectionDot(Connection.Connecting))
        assertEquals(Dot.WARN, connectionDot(Connection.Degraded(since = 0)))
        assertEquals(Dot.ERROR, connectionDot(Connection.Offline))
    }

    @Test
    fun `the host is the URL without its scheme or trailing slash, and the sidebar stays 160 to 260`() {
        assertEquals("127.0.0.1:8030", hostOf("http://127.0.0.1:8030/"))
        assertEquals("dl.example.com", hostOf("https://dl.example.com"))
        assertEquals(160f, clampSidebar(90f))
        assertEquals(200f, clampSidebar(200f))
        assertEquals(260f, clampSidebar(400f))
    }
}
