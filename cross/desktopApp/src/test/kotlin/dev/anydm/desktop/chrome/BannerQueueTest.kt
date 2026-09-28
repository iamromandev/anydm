package dev.anydm.desktop.chrome

import dev.anydm.store.Tone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BannerQueueTest {
    private var clock = 0L
    private val queue = BannerQueue { clock }

    @Test
    fun `a banner shows for 4 s, or 8 s with an action, then the next one`() {
        queue.push(Banner(Tone.INFO, "one"))
        queue.push(Banner(Tone.SUCCESS, "Saved README", action = "Reveal"))
        assertEquals("one", queue.current.value?.message)
        clock = 3_999
        queue.tick()
        assertEquals("one", queue.current.value?.message)
        clock = 4_000
        queue.tick()
        assertEquals("Saved README", queue.current.value?.message)
        clock = 11_999
        queue.tick()
        assertEquals("Saved README", queue.current.value?.message)
        clock = 12_000
        queue.tick()
        assertNull(queue.current.value)
    }

    @Test
    fun `hovering holds the banner, and the time left resumes after`() {
        queue.push(Banner(Tone.INFO, "hold"))
        clock = 3_000
        queue.hover(true)
        clock = 60_000
        queue.tick()
        assertEquals("hold", queue.current.value?.message)
        queue.hover(false)
        clock = 60_999
        queue.tick()
        assertEquals("hold", queue.current.value?.message)
        clock = 61_000
        queue.tick()
        assertNull(queue.current.value)
    }

    @Test
    fun `dismissing shows the next one at once`() {
        queue.push(Banner(Tone.ERROR, "a"))
        queue.push(Banner(Tone.INFO, "b"))
        queue.dismiss()
        assertEquals("b", queue.current.value?.message)
    }
}
