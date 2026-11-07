package dev.anydm.desktop

import dev.anydm.engine.APP_NAME
import kotlin.test.Test
import kotlin.test.assertEquals

class MainTest {
    @Test
    fun windowTakesTheSharedAppName() {
        assertEquals("anydm", APP_NAME)
    }
}
