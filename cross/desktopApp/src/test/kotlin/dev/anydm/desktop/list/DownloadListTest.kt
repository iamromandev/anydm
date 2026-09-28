package dev.anydm.desktop.list

import dev.anydm.store.ListFilter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DownloadListTest {
    @Test
    fun `each filter has its own empty line`() {
        assertEquals("No downloads yet. Paste a link above.", emptyText(ListFilter.ALL))
        assertEquals("Nothing downloading right now.", emptyText(ListFilter.ACTIVE))
        assertEquals("Nothing seeding.", emptyText(ListFilter.SEEDING))
        assertEquals("Nothing finished yet.", emptyText(ListFilter.COMPLETED))
    }

    @Test
    fun `more loads near the end, once, and only while there is more`() {
        assertTrue(wantsMore(lastVisible = 20, total = 25, page = 1, totalPages = 3, loading = false))
        assertFalse(wantsMore(lastVisible = 10, total = 25, page = 1, totalPages = 3, loading = false))
        assertFalse(wantsMore(lastVisible = 24, total = 25, page = 3, totalPages = 3, loading = false))
        assertFalse(wantsMore(lastVisible = 24, total = 25, page = 1, totalPages = 3, loading = true))
    }
}
