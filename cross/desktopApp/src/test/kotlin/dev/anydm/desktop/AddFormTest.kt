package dev.anydm.desktop

import dev.anydm.engine.core.AudioFormat
import dev.anydm.engine.core.SponsorBlockMode
import dev.anydm.engine.core.SubtitleChoice
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class AddFormTest {
    @Test
    fun `lines are trimmed, blanks dropped and repeats kept once`() {
        val lines = parseInput("  https://a.example/x.zip \n\n https://a.example/x.zip\nhttps://b.example/y.zip")
        assertEquals(listOf("https://a.example/x.zip", "https://b.example/y.zip"), lines.map { it.text })
    }

    @Test
    fun `a batch pattern expands to its links`() {
        val lines = parseInput("https://a.example/a[1-3].bin")
        assertEquals(listOf("https://a.example/a1.bin", "https://a.example/a2.bin", "https://a.example/a3.bin"), lines.map { it.text })
        assertTrue(lines.all { it is InputLine.Link })
    }

    @Test
    fun `a pattern that runs backwards says why`() {
        val line = parseInput("https://a.example/a[3-1].bin").single()
        assertIs<InputLine.Invalid>(line)
        assertTrue(line.reason.contains("backwards"))
    }

    @Test
    fun `an address with a plain bracket stays as it is`() {
        assertEquals(listOf("http://[::1]:8080/f"), parseInput("http://[::1]:8080/f").map { it.text })
    }

    private val now = Instant.parse("2026-10-30T12:00:00Z")
    private val utc = TimeZone.UTC

    private fun errors(
        options: AddOptions,
        folders: Set<String> = setOf("/dl"),
    ) = validate(options, now, utc) { it in folders }

    @Test
    fun `valid options have no errors`() {
        assertEquals(emptyMap(), errors(AddOptions()))
        assertEquals(emptyMap(), errors(AddOptions(folder = "/dl", start = StartChoice.AT, startAt = "2026-10-31 08:00")))
    }

    @Test
    fun `a start in the past or not a time is refused`() {
        assertTrue(Field.START_AT in errors(AddOptions(start = StartChoice.AT, startAt = "2026-10-29 08:00")))
        assertTrue(Field.START_AT in errors(AddOptions(start = StartChoice.AT, startAt = "tomorrow")))
    }

    @Test
    fun `a folder that is not there is refused`() {
        assertTrue(Field.FOLDER in errors(AddOptions(folder = "/nowhere")))
    }

    @Test
    fun `playlist range and limit follow the engine's rules`() {
        val media = AddOptions(mode = AddMode.MEDIA, playlist = true)
        assertEquals(emptyMap(), errors(media.copy(range = "1:5,8", limit = "20")))
        assertTrue(Field.RANGE in errors(media.copy(range = "one to five")))
        assertTrue(Field.LIMIT in errors(media.copy(limit = "0")))
        assertTrue(Field.LIMIT in errors(media.copy(limit = "x")))
    }

    @Test
    fun `subtitle languages must be language codes`() {
        val media = AddOptions(mode = AddMode.MEDIA)
        assertEquals(emptyMap(), errors(media.copy(subtitles = "en, de")))
        assertTrue(Field.SUBTITLES in errors(media.copy(subtitles = "english!")))
    }

    @Test
    fun `start at is read in the given zone`() {
        assertEquals(
            Instant.parse("2026-10-31T08:00:00Z"),
            startInstant(AddOptions(start = StartChoice.AT, startAt = "2026-10-31 08:00"), utc),
        )
        assertNull(startInstant(AddOptions(), utc))
    }

    @Test
    fun `media choices make the engine's format`() {
        assertEquals(720, toFormat(AddOptions(preset = MediaPreset.P720)).height)
        assertNull(toFormat(AddOptions(preset = MediaPreset.BEST)).height)
        assertEquals(AudioFormat.MP3, toFormat(AddOptions(preset = MediaPreset.MP3)).audioOnly)
        val format = toFormat(AddOptions(subtitles = "en, de", autoSubtitles = true, sponsor = SponsorChoice.CUT))
        assertEquals(SubtitleChoice(listOf("en", "de"), includeAuto = true), format.subtitles)
        assertEquals(SponsorBlockMode.CUT, format.sponsorBlock?.mode)
        assertNull(toFormat(AddOptions()).subtitles)
        assertNull(toFormat(AddOptions()).sponsorBlock)
    }

    @Test
    fun `a login goes into the address, encoded`() {
        assertEquals("sftp://me:p%40ss%20w@host/f.iso", withLogin("sftp://host/f.iso", "me", "p@ss w"))
        assertEquals("ftp://me@host/f", withLogin("ftp://host/f", "me", ""))
        assertEquals("ftp://host/f", withLogin("ftp://host/f", "", "x"))
        // A login already in the address is kept.
        assertEquals("ftp://a:b@host/f", withLogin("ftp://a:b@host/f", "me", "x"))
    }

    @Test
    fun `a login the address can't carry, or a key without a user, is refused`() {
        assertTrue(Field.LOGIN in errors(AddOptions(user = "a:b", password = "x")))
        assertTrue(Field.LOGIN in errors(AddOptions(user = "me\n", password = "x")))
        assertTrue(Field.LOGIN in errors(AddOptions(keyPath = "/keys/id")))
        assertEquals(emptyMap(), errors(AddOptions(user = "me", keyPath = "/keys/id")))
    }

    @Test
    fun `a login is hidden when an address is shown`() {
        assertEquals("ftp://host/f", withoutLogin("ftp://me:pw@host/f"))
        assertEquals("https://a.example/x@y", withoutLogin("https://a.example/x@y"))
    }
}
