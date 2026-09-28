package dev.anydm.desktop.files

import dev.anydm.settings.Player
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlayerTest {
    @Test
    fun `the system player opens the URL itself`() {
        assertNull(playCommand(Player.System, "http://a/f"))
    }

    @Test
    fun `a chosen player is launched with the URL, a mac app through open`() {
        assertEquals(listOf("/usr/bin/mpv", "http://a/f"), playCommand(Player.Command("/usr/bin/mpv"), "http://a/f", "Linux"))
        assertEquals(
            listOf("open", "-a", "/Applications/VLC.app", "http://a/f"),
            playCommand(Player.Command("/Applications/VLC.app"), "http://a/f", "Mac OS X"),
        )
    }

    @Test
    fun `media is told by its extension`() {
        assertEquals(true, isMedia("Big Buck Bunny.MKV"))
        assertEquals(true, isMedia("song.mp3"))
        assertEquals(false, isMedia("poster.jpg"))
        assertEquals(false, isMedia("README"))
    }
}
