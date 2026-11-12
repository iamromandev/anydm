package dev.anydm.engine.http

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SpeedMeterTest {
    @Test
    fun emptyMeterIsZero() = assertEquals(0, SpeedMeter().bytesPerSec(5_000))

    @Test
    fun steadyStreamAveragesToItsRate() {
        val m = SpeedMeter()
        var t = 0L
        while (t <= 10_000) {
            m.add(t, 100) // 100 B per 100 ms = 1000 B/s
            t += 100
        }
        val v = m.bytesPerSec(10_000)
        assertTrue(v in 950..1_050, "got $v")
    }

    @Test
    fun oldSamplesExpire() {
        val m = SpeedMeter()
        m.add(0, 5_000)
        assertEquals(0, m.bytesPerSec(10_000))
    }

    @Test
    fun youngMeterDoesNotDivideByLessThanOneSecond() {
        val m = SpeedMeter()
        m.add(0, 500)
        assertEquals(500, m.bytesPerSec(500))
    }
}
