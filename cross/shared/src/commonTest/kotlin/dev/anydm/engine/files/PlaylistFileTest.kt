package dev.anydm.engine.files

import io.kotest.matchers.shouldBe
import kotlin.test.Test

class PlaylistFileTest {
    @Test
    fun entriesAreListedInOrderWithTheirLength() {
        PlaylistFile.render(
            listOf(
                PlaylistFileEntry("One", 61, "One.mp4"),
                PlaylistFileEntry("Two", null, "sub folder/Two.mp4"),
            ),
        ) shouldBe "#EXTM3U\n#EXTINF:61,One\nOne.mp4\n#EXTINF:-1,Two\nsub folder/Two.mp4\n"
    }

    @Test
    fun aTitleStaysOnOneLine() {
        PlaylistFile.render(listOf(PlaylistFileEntry("a\nb\r\nc d\u0000e", 1, "x.mp4"))) shouldBe
            "#EXTM3U\n#EXTINF:1,a b  c d e\nx.mp4\n"
    }

    @Test
    fun aPathWithALineBreakDropsItsEntry() {
        PlaylistFile.render(
            listOf(
                PlaylistFileEntry("Bad", 1, "a\nb.mp4"),
                PlaylistFileEntry("Bad too", 1, "a\u0000b.mp4"),
                PlaylistFileEntry("Good", 1, "g.mp4"),
            ),
        ) shouldBe "#EXTM3U\n#EXTINF:1,Good\ng.mp4\n"
    }

    @Test
    fun aNameThatLooksLikeACommentIsMadeRelative() {
        PlaylistFile.render(listOf(PlaylistFileEntry("Hash", 1, "#1 hit.mp4"))) shouldBe "#EXTM3U\n#EXTINF:1,Hash\n./#1 hit.mp4\n"
    }

    @Test
    fun noEntriesIsJustTheHeader() {
        PlaylistFile.render(emptyList()) shouldBe "#EXTM3U\n"
    }
}
