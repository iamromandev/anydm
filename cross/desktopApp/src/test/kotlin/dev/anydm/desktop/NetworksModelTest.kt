package dev.anydm.desktop

import dev.anydm.engine.net.BindStatus
import dev.anydm.engine.net.BondedNetwork
import dev.anydm.engine.net.BondingState
import dev.anydm.engine.net.NetworkInfo
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
internal class NetworksModelTest {
    private fun network(
        key: String,
        name: String = key,
        speed: Double? = null,
        up: Boolean = true,
        faulted: Boolean = false,
        enabled: Boolean = true,
    ) = BondedNetwork(NetworkInfo(key, name, up, emptyList()), enabled, BindStatus.ISOLATED, speed, faulted)

    private class Setup(
        val doctor: MutableList<Unit>,
        val source: FakeSource,
        val visuals: NetworkVisuals,
        val model: NetworksModel,
        val editor: NetworkEditor,
        val limits: MutableList<String?>,
    )

    private fun menu(block: suspend TestScope.(Setup) -> Unit) =
        runTest(UnconfinedTestDispatcher()) {
            val source = FakeSource()
            source.networks.value =
                BondingState(listOf(network("usb0", "USB Tethering", speed = 3_000_000.0), network("en0", "Wi-Fi")))
            val visuals = NetworkVisuals(null)
            val looks = NetworkLooksModel(source, visuals, backgroundScope)
            val limits = mutableListOf<String?>()
            val doctor = mutableListOf<Unit>()
            val model = NetworksModel(source, looks.looks, backgroundScope, onOpenDoctor = { doctor += Unit }) { limits += it }
            block(Setup(doctor, source, visuals, model, NetworkEditor(visuals, looks.looks), limits))
        }

    @Test
    fun `each network is a row with its color, its OS name, its kind tag and its live speed or Idle`() =
        menu { s ->
            val rows = s.model.state.value.rows
            assertEquals(listOf("en0", "usb0"), rows.map { it.key })
            val wifi = rows[0]
            assertEquals("Wi-Fi", wifi.name)
            assertEquals("Wi-Fi", wifi.osName)
            assertEquals("WIFI", wifi.kind.tag)
            assertEquals("teal", wifi.colorId)
            assertNull(wifi.speed)
            val usb = rows[1]
            assertEquals("USB", usb.kind.tag)
            assertEquals("amber", usb.colorId)
            assertEquals(3_000_000L, usb.speed)
        }

    @Test
    fun `a network that carries nothing is Idle`() =
        menu { s ->
            s.source.networks.value = BondingState(listOf(network("en0", "Wi-Fi", speed = 0.0)))
            assertNull(
                s.model.state.value.rows
                    .single()
                    .speed,
            )
        }

    @Test
    fun `a rename and a pick show in the rows`() =
        menu { s ->
            s.visuals.rename("usb0", "Pixel")
            s.visuals.pick("usb0", "rose")
            val usb =
                s.model.state.value.rows
                    .first { it.key == "usb0" }
            assertEquals("Pixel", usb.name)
            assertEquals("USB Tethering", usb.osName)
            assertEquals("rose", usb.colorId)
        }

    @Test
    fun `a network that is down says so`() =
        menu { s ->
            s.source.networks.value = BondingState(listOf(network("en0", "Wi-Fi", up = false)))
            assertFalse(
                s.model.state.value.rows
                    .single()
                    .up,
            )
        }

    @Test
    fun `no network at all is no rows`() =
        menu { s ->
            s.source.networks.value = BondingState(emptyList())
            assertTrue(
                s.model.state.value.rows
                    .isEmpty(),
            )
        }

    @Test
    fun `the menu opens, closes and toggles`() =
        menu { s ->
            assertFalse(s.model.state.value.open)
            s.model.toggle()
            assertTrue(s.model.state.value.open)
            s.model.close()
            assertFalse(s.model.state.value.open)
            s.model.open()
            s.model.open()
            assertTrue(s.model.state.value.open)
        }

    @Test
    fun `a row's chevron and the limits link ask for limits, and close the menu`() =
        menu { s ->
            s.model.open()
            s.model.openLimits("usb0")
            assertFalse(s.model.state.value.open)
            s.model.open()
            s.model.openLimits(null)
            assertEquals(listOf<String?>("usb0", null), s.limits)
        }

    // The edit popover

    @Test
    fun `editing starts from the name and color the network has now`() =
        menu { s ->
            s.editor.start("usb0", "menu", "USB")
            val edit = assertNotNull(s.editor.editing.value)
            assertEquals("USB", edit.name)
            assertEquals("amber", edit.colorId)
            assertEquals("menu", edit.anchor)
        }

    @Test
    fun `save renames and recolors, and the popover closes`() =
        menu { s ->
            s.editor.start("usb0", "menu", "USB")
            s.editor.setName("  Pixel ")
            s.editor.setColor("rose")
            assertTrue(s.editor.save())
            assertNull(s.editor.editing.value)
            assertEquals(NetworkPref("Pixel", "rose"), s.visuals.prefs.value["usb0"])
        }

    @Test
    fun `an empty name keeps the popover open with a reason and saves nothing`() =
        menu { s ->
            s.editor.start("usb0", "menu", "USB")
            s.editor.setName("   ")
            s.editor.setColor("rose")
            assertFalse(s.editor.save())
            assertNotNull(
                s.editor.editing.value
                    ?.error,
            )
            assertTrue(
                s.visuals.prefs.value
                    .isEmpty(),
            )
        }

    @Test
    fun `cancel throws the draft away`() =
        menu { s ->
            s.editor.start("usb0", "menu", "USB")
            s.editor.setName("Pixel")
            s.editor.cancel()
            assertNull(s.editor.editing.value)
            assertTrue(
                s.visuals.prefs.value
                    .isEmpty(),
            )
        }

    @Test
    fun `saving without a change writes nothing, and the color stays automatic`() =
        menu { s ->
            s.editor.start("usb0", "menu", "USB")
            assertTrue(s.editor.save())
            assertTrue(
                s.visuals.prefs.value
                    .isEmpty(),
            )
        }

    @Test
    fun `picking the swatch a network was automatically given pins it`() =
        menu { s ->
            s.editor.start("usb0", "menu", "USB")
            s.editor.setColor("amber")
            assertTrue(s.editor.save())
            assertEquals(
                "amber",
                s.visuals.prefs.value["usb0"]
                    ?.color,
            )
        }

    @Test
    fun `a color touched and put back on the shown one still pins it`() =
        menu { s ->
            s.editor.start("usb0", "menu", "USB")
            s.editor.setColor("rose")
            s.editor.setColor("amber")
            assertTrue(s.editor.save())
            assertEquals(
                "amber",
                s.visuals.prefs.value["usb0"]
                    ?.color,
            )
        }

    @Test
    fun `renaming alone does not pin the color`() =
        menu { s ->
            s.editor.start("usb0", "menu", "USB")
            s.editor.setName("Pixel")
            assertTrue(s.editor.save())
            assertNull(
                s.visuals.prefs.value["usb0"]
                    ?.color,
            )
        }

    @Test
    fun `the name field can't hold more than forty characters`() =
        menu { s ->
            s.editor.start("usb0", "menu", "USB")
            s.editor.setName("x".repeat(60))
            assertEquals(
                MAX_NETWORK_NAME,
                s.editor.editing.value!!
                    .name.length,
            )
        }

    @Test
    fun `a name that could not be written is kept for this run and said so, then the next save closes`() =
        runTest(UnconfinedTestDispatcher()) {
            val source = FakeSource()
            source.networks.value = BondingState(listOf(network("en0", "Wi-Fi")))
            val dir =
                java.nio.file.Files
                    .createTempDirectory("anydm-editor")
            val blocker = dir.resolve("blocker")
            java.nio.file.Files
                .writeString(blocker, "x")
            val visuals = NetworkVisuals(blocker.resolve("network-visuals.json"))
            val looks = NetworkLooksModel(source, visuals, backgroundScope)
            val editor = NetworkEditor(visuals, looks.looks)
            editor.start("en0", "menu", "Wi-Fi")
            editor.setName("Home")
            assertFalse(editor.save())
            assertNotNull(editor.editing.value?.error)
            assertEquals("Home", looks.looks.value.names["en0"])
            assertTrue(editor.save())
            assertNull(editor.editing.value)
        }

    @Test
    fun `only one popover is open at a time`() =
        menu { s ->
            s.editor.start("usb0", "menu", "USB")
            s.editor.start("en0", "table", "Wi-Fi")
            assertEquals(
                "en0",
                s.editor.editing.value!!
                    .key,
            )
            assertEquals(
                "table",
                s.editor.editing.value!!
                    .anchor,
            )
        }

    @Test
    fun `an idle, a down and a failing network can ask why they add no speed, a busy one cannot`() =
        menu { s ->
            s.source.networks.value =
                BondingState(
                    listOf(
                        network("busy", speed = 3_000_000.0),
                        network("idle"),
                        network("down", up = false),
                        network("bad", speed = 1_000.0, faulted = true),
                    ),
                )
            val help =
                s.model.state.value.rows
                    .associate { it.key to it.canDiagnose }
            assertEquals(mapOf("bad" to true, "busy" to false, "down" to true, "idle" to true), help)
        }

    @Test
    fun `a network switched off does not offer the why link`() =
        menu { s ->
            s.source.networks.value = BondingState(listOf(network("off", enabled = false), network("on")))
            val help =
                s.model.state.value.rows
                    .associate { it.key to it.canDiagnose }
            assertEquals(mapOf("off" to false, "on" to true), help)
        }

    @Test
    fun `the doctor entry closes the menu and opens the doctor`() =
        menu { s ->
            s.model.open()
            s.model.openDoctor()
            assertFalse(s.model.state.value.open)
            assertEquals(1, s.doctor.size)
        }
}
