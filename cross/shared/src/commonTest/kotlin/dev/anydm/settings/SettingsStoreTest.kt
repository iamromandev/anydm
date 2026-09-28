package dev.anydm.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class MemoryFile(
    var text: String? = null,
) : SettingsFile {
    override fun read() = text

    override fun write(text: String) {
        this.text = text
    }
}

class SettingsStoreTest {
    @Test
    fun `nothing saved yet reads as the defaults`() {
        assertEquals(Settings(), SettingsStore(MemoryFile()).settings.value)
    }

    @Test
    fun `a change is kept, and read back by the next store`() {
        val file = MemoryFile()
        SettingsStore(file).update { it.copy(serverUrl = "http://nas:8030", apiKey = "k", player = Player.Command("/usr/bin/vlc")) }

        val again = SettingsStore(file).settings.value
        assertEquals("http://nas:8030", again.serverUrl)
        assertEquals("k", again.apiKey)
        assertEquals(Player.Command("/usr/bin/vlc"), again.player)
        assertTrue(file.text!!.contains("\"serverUrl\""))
    }

    @Test
    fun `a damaged or older file falls back field by field, never failing`() {
        assertEquals(Settings(), SettingsStore(MemoryFile("{not json")).settings.value)
        val older = SettingsStore(MemoryFile("""{"serverUrl":"http://a","somethingOld":1}""")).settings.value
        assertEquals("http://a", older.serverUrl)
        assertEquals(true, older.confirmBeforeRemove)
    }
}
