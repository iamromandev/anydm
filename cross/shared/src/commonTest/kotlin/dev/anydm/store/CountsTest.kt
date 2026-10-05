package dev.anydm.store

import dev.anydm.model.TaskStatus.CANCELLED
import dev.anydm.model.TaskStatus.COMPLETED
import dev.anydm.model.TaskStatus.DOWNLOADING
import dev.anydm.model.TaskStatus.FAILED
import dev.anydm.model.TaskStatus.PAUSED
import dev.anydm.model.TaskStatus.PENDING
import dev.anydm.model.TaskStatus.QUEUED
import dev.anydm.model.TaskStatus.SEEDING
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CountsTest {
    @Test
    fun `a pause, a resume, a finish and a remove move the counts`() {
        assertTrue(countsMoved(DOWNLOADING, PAUSED))
        assertTrue(countsMoved(PAUSED, PENDING))
        assertTrue(countsMoved(DOWNLOADING, COMPLETED))
        assertTrue(countsMoved(DOWNLOADING, SEEDING))
        assertTrue(countsMoved(COMPLETED, CANCELLED))
        assertTrue(countsMoved(FAILED, PENDING))
    }

    @Test
    fun `a move within one count, or a row new to the list, does not`() {
        assertFalse(countsMoved(PENDING, DOWNLOADING))
        assertFalse(countsMoved(QUEUED, DOWNLOADING))
        assertFalse(countsMoved(PAUSED, FAILED))
        assertFalse(countsMoved(null, DOWNLOADING))
    }
}
