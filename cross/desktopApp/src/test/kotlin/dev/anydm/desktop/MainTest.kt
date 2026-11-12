package dev.anydm.desktop

import dev.anydm.engine.APP_NAME
import kotlin.test.Test
import kotlin.test.assertEquals

class MainTest {
    @Test
    fun windowTakesTheSharedAppName() {
        assertEquals("anydm", APP_NAME)
    }

    @Test
    fun theWindowStartsWithoutDownloadRowsButWithTheNetworks() {
        for (prop in listOf(null, "empty")) {
            assertEquals(emptyList(), startState(prop).downloads)
        }
        assertEquals(2, startState(null).networks.size)
        assertEquals(1, startState("empty").networks.size)
    }
}
