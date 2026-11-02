package dev.anydm.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

internal class PendingOpensTest {
    private fun PendingOpens.next() = requests.tryReceive().getOrNull()

    @Test
    fun `offered arguments reach the window in the order they came, once`() {
        val pending = PendingOpens()
        pending.offer(listOf("magnet:?xt=urn:btih:a"))
        pending.offer(listOf("/tmp/a.torrent"))
        assertEquals(listOf("magnet:?xt=urn:btih:a"), pending.next())
        assertEquals(listOf("/tmp/a.torrent"), pending.next())
        assertNull(pending.next())
    }

    @Test
    fun `an empty launch is not an open`() {
        val pending = PendingOpens()
        pending.offer(emptyList())
        assertNull(pending.next())
    }

    @Test
    fun `the menu's Add asks for a blank dialog`() {
        val pending = PendingOpens()
        pending.openBlank()
        assertEquals(emptyList<String>(), pending.next())
        assertNull(pending.next())
    }
}
