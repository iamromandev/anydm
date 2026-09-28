package dev.anydm.settings

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import kotlin.test.Test
import kotlin.test.assertEquals

class JvmSettingsFileTest {
    @Test
    fun `each OS keeps it in its own app-data folder`() {
        assertEquals(
            Path.of("/Users/r/Library/Application Support/anydm/settings.json"),
            defaultSettingsPath("Mac OS X", emptyMap(), "/Users/r"),
        )
        assertEquals(
            Path.of("C:\\Users\\r\\AppData\\Roaming", "anydm", "settings.json"),
            defaultSettingsPath("Windows 11", mapOf("APPDATA" to "C:\\Users\\r\\AppData\\Roaming"), "C:\\Users\\r"),
        )
        assertEquals(Path.of("/home/r/.config/anydm/settings.json"), defaultSettingsPath("Linux", emptyMap(), "/home/r"))
        assertEquals(Path.of("/x/anydm/settings.json"), defaultSettingsPath("Linux", mapOf("XDG_CONFIG_HOME" to "/x"), "/home/r"))
    }

    @Test
    fun `it writes the folder it needs, readable by its owner alone`() {
        val dir = Files.createTempDirectory("anydm-settings")
        val path = dir.resolve("nested/settings.json")
        val file = JvmSettingsFile(path)

        assertEquals(null, file.read())
        file.write("""{"serverUrl":"http://a"}""")

        assertEquals("""{"serverUrl":"http://a"}""", file.read())
        if (Files.getFileStore(path).supportsFileAttributeView("posix")) {
            assertEquals(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), Files.getPosixFilePermissions(path))
        }
    }
}
