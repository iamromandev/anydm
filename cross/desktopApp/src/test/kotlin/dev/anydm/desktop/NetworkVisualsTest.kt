package dev.anydm.desktop

import dev.anydm.engine.net.NetworkInfo
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class NetworkVisualsTest {
    private fun folder(): Path = Files.createTempDirectory("anydm-visuals")

    private fun info(
        key: String,
        name: String = key,
    ) = NetworkInfo(key, name, true, listOf("192.168.1.5"))

    // The file

    @Test
    fun `a name and a color survive a restart`() {
        val file = folder().resolve("network-visuals.json")
        val first = NetworkVisuals(file)
        assertEquals(VisualsResult.Saved, first.rename("en7", "Pixel"))
        assertEquals(VisualsResult.Saved, first.pick("en7", "rose"))
        val second = NetworkVisuals(file)
        assertEquals(NetworkPref(name = "Pixel", color = "rose"), second.prefs.value["en7"])
    }

    @Test
    fun `the file is keyed by network id`() {
        val file = folder().resolve("network-visuals.json")
        val visuals = NetworkVisuals(file)
        visuals.rename("en0", "Home")
        visuals.pick("en7", "violet")
        val json = Files.readString(file)
        assertTrue("\"en0\"" in json && "\"en7\"" in json, json)
        assertTrue("\"Home\"" in json && "\"violet\"" in json, json)
    }

    @Test
    fun `a name is trimmed`() {
        val visuals = NetworkVisuals(null)
        assertEquals(VisualsResult.Saved, visuals.rename("en0", "  Pixel  "))
        assertEquals(
            "Pixel",
            visuals.prefs.value
                .getValue("en0")
                .name,
        )
    }

    @Test
    fun `an empty or blank name is rejected and changes nothing`() {
        val file = folder().resolve("network-visuals.json")
        val visuals = NetworkVisuals(file)
        visuals.rename("en0", "Home")
        for (raw in listOf("", "   ", "\t\n")) {
            assertIs<VisualsResult.Invalid>(visuals.rename("en0", raw), raw)
        }
        assertEquals(
            "Home",
            visuals.prefs.value
                .getValue("en0")
                .name,
        )
        assertEquals(
            "Home",
            NetworkVisuals(file)
                .prefs.value
                .getValue("en0")
                .name,
        )
    }

    @Test
    fun `a name of forty characters is taken and one of forty-one is not`() {
        val visuals = NetworkVisuals(null)
        assertEquals(VisualsResult.Saved, visuals.rename("en0", "a".repeat(40)))
        assertIs<VisualsResult.Invalid>(visuals.rename("en0", "b".repeat(41)))
        assertEquals(
            "a".repeat(40),
            visuals.prefs.value
                .getValue("en0")
                .name,
        )
    }

    @Test
    fun `the forty characters count after trimming`() {
        val visuals = NetworkVisuals(null)
        assertEquals(VisualsResult.Saved, visuals.rename("en0", "  " + "a".repeat(40) + "  "))
    }

    @Test
    fun `a color that is not one of the eight swatches is rejected`() {
        val visuals = NetworkVisuals(null)
        assertIs<VisualsResult.Invalid>(visuals.pick("en0", "mauve"))
        assertNull(visuals.prefs.value["en0"])
        for (id in SWATCH_IDS) assertEquals(VisualsResult.Saved, visuals.pick("en0", id))
        assertEquals(8, SWATCH_IDS.size)
    }

    @Test
    fun `a rename keeps the color and a pick keeps the name, and other networks are left alone`() {
        val visuals = NetworkVisuals(null)
        visuals.pick("en0", "lime")
        visuals.rename("en0", "Home")
        visuals.rename("en7", "Pixel")
        visuals.pick("en7", "coral")
        assertEquals(NetworkPref("Home", "lime"), visuals.prefs.value["en0"])
        assertEquals(NetworkPref("Pixel", "coral"), visuals.prefs.value["en7"])
    }

    @Test
    fun `a missing file is no choices`() {
        assertTrue(NetworkVisuals(folder().resolve("none.json")).prefs.value.isEmpty())
    }

    @Test
    fun `a file that is not json is ignored and the next change replaces it`() {
        val file = folder().resolve("network-visuals.json")
        Files.writeString(file, "{ this is not json")
        val visuals = NetworkVisuals(file)
        assertTrue(visuals.prefs.value.isEmpty())
        assertEquals(VisualsResult.Saved, visuals.rename("en0", "Home"))
        assertEquals(
            "Home",
            NetworkVisuals(file)
                .prefs.value
                .getValue("en0")
                .name,
        )
    }

    @Test
    fun `a file that is not json is kept as a backup before the next write replaces it`() {
        val file = folder().resolve("network-visuals.json")
        Files.writeString(file, "{ this is not json")
        val visuals = NetworkVisuals(file)
        // Nothing is touched until something is written.
        assertFalse(Files.exists(file.resolveSibling("network-visuals.json.bak")))
        visuals.rename("en0", "Home")
        assertEquals("{ this is not json", Files.readString(file.resolveSibling("network-visuals.json.bak")))
        // A second write does not overwrite the backup with the good file.
        visuals.rename("en0", "Home 2")
        assertEquals("{ this is not json", Files.readString(file.resolveSibling("network-visuals.json.bak")))
    }

    @Test
    fun `a file with entries that break the rules is kept as a backup before the next write`() {
        val file = folder().resolve("network-visuals.json")
        val text = """{"ok":{"name":"Fine"},"badcolor":{"name":"Hi","color":"mauve"}}"""
        Files.writeString(file, text)
        val visuals = NetworkVisuals(file)
        assertFalse(Files.exists(file.resolveSibling("network-visuals.json.bak")))
        visuals.rename("en0", "Home")
        assertEquals(text, Files.readString(file.resolveSibling("network-visuals.json.bak")))
    }

    @Test
    fun `a backup that could not be made is tried again at the next write`() {
        val file = folder().resolve("network-visuals.json")
        Files.writeString(file, "{ this is not json")
        // A folder where the backup goes makes the copy fail.
        Files.createDirectory(file.resolveSibling("network-visuals.json.bak")).resolve("x").let { Files.writeString(it, "x") }
        val visuals = NetworkVisuals(file)
        visuals.rename("en0", "Home")
        assertEquals(true, Files.isDirectory(file.resolveSibling("network-visuals.json.bak")))
        // The obstacle goes: the next write backs up what the broken file held, not the file written since.
        Files.delete(file.resolveSibling("network-visuals.json.bak").resolve("x"))
        Files.delete(file.resolveSibling("network-visuals.json.bak"))
        visuals.rename("en0", "Home 2")
        assertEquals("{ this is not json", Files.readString(file.resolveSibling("network-visuals.json.bak")))
    }

    @Test
    fun `a good file leaves no backup`() {
        val file = folder().resolve("network-visuals.json")
        NetworkVisuals(file).rename("en0", "Home")
        assertFalse(Files.exists(file.resolveSibling("network-visuals.json.bak")))
    }

    @Test
    fun `entries that break the rules are dropped when the file is read`() {
        val file = folder().resolve("network-visuals.json")
        Files.writeString(
            file,
            """{"ok":{"name":"Fine","color":"teal"},"long":{"name":"${"x".repeat(41)}","color":"amber"},""" +
                """"blank":{"name":"  ","color":"steel"},"badcolor":{"name":"Hi","color":"mauve"},"junk":5}""",
        )
        val prefs = NetworkVisuals(file).prefs.value
        assertEquals(NetworkPref("Fine", "teal"), prefs["ok"])
        // A bad name drops only the name; a bad color drops only the color.
        assertEquals(NetworkPref(null, "amber"), prefs["long"])
        assertEquals(NetworkPref(null, "steel"), prefs["blank"])
        assertEquals(NetworkPref("Hi", null), prefs["badcolor"])
        assertFalse("junk" in prefs)
    }

    @Test
    fun `a file that can't be written still applies the change for this run and says so`() {
        val dir = folder()
        val blocker = dir.resolve("blocker")
        Files.writeString(blocker, "a file where a folder is needed")
        val visuals = NetworkVisuals(blocker.resolve("network-visuals.json"))
        assertIs<VisualsResult.NotSaved>(visuals.rename("en0", "Home"))
        assertEquals(
            "Home",
            visuals.prefs.value
                .getValue("en0")
                .name,
        )
    }

    // The checks on their own

    @Test
    fun `the name check trims, then asks for something and no more than forty`() {
        assertEquals(NameCheck.Ok("Pixel"), checkNetworkName(" Pixel "))
        assertEquals(NameCheck.Empty, checkNetworkName("  "))
        assertEquals(NameCheck.TooLong, checkNetworkName("a".repeat(41)))
        assertEquals(NameCheck.Ok("a".repeat(40)), checkNetworkName("a".repeat(40)))
    }

    // Kinds

    @Test
    fun `kinds are read from the names the OS gives`() {
        assertEquals(NetworkKind.WIFI, kindOf(info("wlan0")))
        assertEquals(NetworkKind.WIFI, kindOf(info("wlp2s0")))
        assertEquals(NetworkKind.WIFI, kindOf(info("{GUID}", "Intel(R) Wi-Fi 6 AX201 160MHz")))
        assertEquals(NetworkKind.WIFI, kindOf(info("en0")))
        assertEquals(NetworkKind.USB, kindOf(info("usb0")))
        assertEquals(NetworkKind.USB, kindOf(info("enx00e04c680001")))
        assertEquals(NetworkKind.USB, kindOf(info("{GUID}", "Remote NDIS based Internet Sharing Device")))
        assertEquals(NetworkKind.ETHERNET, kindOf(info("eth0")))
        assertEquals(NetworkKind.ETHERNET, kindOf(info("enp3s0")))
        assertEquals(NetworkKind.ETHERNET, kindOf(info("{GUID}", "Realtek PCIe GbE Family Controller")))
        assertEquals(NetworkKind.ETHERNET, kindOf(info("{GUID}", "Ethernet 2")))
        assertEquals(NetworkKind.OTHER, kindOf(info("en7")))
        assertEquals(NetworkKind.OTHER, kindOf(info("feth0")))
    }

    // Colors

    private fun ids(
        kinds: Map<String, NetworkKind>,
        pinned: Map<String, String> = emptyMap(),
    ) = assignSwatchIds(kinds, pinned)

    @Test
    fun `a network takes the hue of its kind`() {
        val picked = ids(mapOf("en0" to NetworkKind.WIFI, "en7" to NetworkKind.USB, "eth0" to NetworkKind.ETHERNET))
        assertEquals("teal", picked["en0"])
        assertEquals("amber", picked["en7"])
        assertEquals("steel", picked["eth0"])
    }

    @Test
    fun `two networks of one kind don't share a hue, the second takes the first free swatch`() {
        val picked = ids(mapOf("usb0" to NetworkKind.USB, "usb1" to NetworkKind.USB))
        assertEquals("amber", picked["usb0"])
        assertEquals("teal", picked["usb1"])
    }

    @Test
    fun `a network of no kind takes the first swatch nobody has`() {
        val picked = ids(mapOf("a" to NetworkKind.WIFI, "b" to NetworkKind.OTHER, "c" to NetworkKind.OTHER))
        assertEquals("teal", picked["a"])
        assertEquals("amber", picked["b"])
        assertEquals("steel", picked["c"])
    }

    @Test
    fun `a pinned color wins over the hue of a kind and is not given to another network`() {
        val picked = ids(mapOf("en0" to NetworkKind.WIFI, "en7" to NetworkKind.USB), pinned = mapOf("en7" to "teal"))
        assertEquals("teal", picked["en7"])
        // en0's own hue is taken, so it takes the first swatch left.
        assertEquals("amber", picked["en0"])
    }

    @Test
    fun `pins on a network that is not there are ignored`() {
        val picked = ids(mapOf("en0" to NetworkKind.WIFI), pinned = mapOf("gone" to "teal"))
        assertEquals(mapOf("en0" to "teal"), picked)
    }

    @Test
    fun `a pin that names no swatch falls back to the kind hue`() {
        val picked = ids(mapOf("en0" to NetworkKind.WIFI), pinned = mapOf("en0" to "mauve"))
        assertEquals("teal", picked["en0"])
    }

    @Test
    fun `two networks may be pinned to one color`() {
        val picked = ids(mapOf("a" to NetworkKind.WIFI, "b" to NetworkKind.USB), pinned = mapOf("a" to "rose", "b" to "rose"))
        assertEquals("rose", picked["a"])
        assertEquals("rose", picked["b"])
    }

    @Test
    fun `more networks than swatches wrap around to the kind's hue or the first swatch`() {
        val kinds = (1..9).associate { "n$it" to NetworkKind.OTHER }
        val picked = ids(kinds)
        assertEquals(SWATCH_IDS, (1..8).map { picked.getValue("n$it") })
        assertEquals("teal", picked["n9"])
    }

    @Test
    fun `colors don't depend on the order the networks were listed in`() {
        val one = ids(linkedMapOf("b" to NetworkKind.USB, "a" to NetworkKind.USB))
        val other = ids(linkedMapOf("a" to NetworkKind.USB, "b" to NetworkKind.USB))
        assertEquals(one, other)
        assertEquals("amber", one["a"])
    }

    @Test
    fun `swatch ids become the theme's swatches`() {
        val swatches = assignSwatches(mapOf("en0" to NetworkKind.WIFI), mapOf("en0" to "rose"), DarkColors)
        assertEquals(DarkColors.swatches[3], swatches.getValue("en0"))
        assertEquals("rose", DarkColors.swatches[3].id)
        assertEquals(SWATCH_IDS, LightColors.swatches.map { it.id })
    }
}
